package com.auralis.crisisconnect.ui.components

import android.content.Context
import android.telephony.TelephonyManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.murgupluoglu.flagkit.FlagKit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One selectable country: ISO region, localized name, calling code, and its flag drawable. */
@Immutable
data class Country(
    val iso: String,
    val name: String,
    val dialCode: Int,
    @DrawableRes val flagRes: Int
)

/** Renders a flat, rectangular vector flag (FlagKit) for the given drawable resource. */
@Composable
private fun CountryFlag(@DrawableRes flagRes: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(flagRes),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(3.dp)),
        contentScale = ContentScale.Fit
    )
}

/** Last-resort country used only if the generated list is somehow empty. */
fun fallbackCountry(): Country = Country("US", "United States", 1, FlagKit.getResId("us"))

/**
 * Best-guess starting country for a fresh phone field: SIM region first (where the number
 * most likely belongs), then the network, then the device locale. Falls back to "US".
 */
fun defaultCountryIso(context: Context): String {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val sim = runCatching { tm?.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
    val network = runCatching { tm?.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
    val locale = Locale.getDefault().country.takeIf { it.isNotBlank() }
    return (sim ?: network ?: locale ?: "US").uppercase(Locale.ROOT)
}

/**
 * Full, correct country list built from libphonenumber (dial codes) and the JVM locale
 * (display names) — rebuilt only when the display language changes.
 *
 * Built OFF the main thread: the first [PhoneNumberUtil] call bootstraps its metadata from the
 * APK (JarURLConnection reads), which ANR'd low-end devices when run in composition (Crashlytics
 * issue 7226142d). Until the list lands the callers fall back to [fallbackCountry] / an empty
 * picker for a few frames; a process-wide cache makes every later composition instant.
 */
@Composable
fun rememberCountryList(): List<Country> {
    val configuration = LocalConfiguration.current
    val displayLocale = remember(configuration) {
        @Suppress("DEPRECATION")
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    val countries by produceState(
        initialValue = countryListCache[displayLocale.language] ?: emptyList(),
        key1 = displayLocale.language
    ) {
        if (value.isEmpty()) {
            value = withContext(Dispatchers.Default) {
                countryListCache.getOrPut(displayLocale.language) { buildCountryList(displayLocale) }
            }
        }
    }
    return countries
}

/** Language tag → built list; libphonenumber metadata never changes within a process. */
private val countryListCache = ConcurrentHashMap<String, List<Country>>()

private fun buildCountryList(displayLocale: Locale): List<Country> {
    val util = PhoneNumberUtil.getInstance()
    return util.supportedRegions.mapNotNull { iso ->
        val dial = util.getCountryCodeForRegion(iso)
        if (dial == 0) return@mapNotNull null
        val name = Locale("", iso).getDisplayCountry(displayLocale).ifBlank { iso }
        Country(iso = iso, name = name, dialCode = dial, flagRes = FlagKit.getResId(iso))
    }.sortedBy { it.name.lowercase(displayLocale) }
}

/** Combines the picked country and typed national number into an E.164 string for Firebase. */
fun composeE164(country: Country, nationalNumber: String): String {
    val util = PhoneNumberUtil.getInstance()
    runCatching {
        val parsed = util.parse(nationalNumber, country.iso)
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }
    // Fallback if libphonenumber can't parse it: naive concat, dropping a national trunk 0.
    val digits = nationalNumber.filter { it.isDigit() }.trimStart('0')
    return "+${country.dialCode}$digits"
}

/** True when the typed number is a valid number for the selected country. */
fun isValidPhoneNumber(country: Country, nationalNumber: String): Boolean =
    runCatching {
        val util = PhoneNumberUtil.getInstance()
        util.isValidNumber(util.parse(nationalNumber, country.iso))
    }.getOrDefault(false)

private fun exampleNationalNumber(country: Country): String =
    runCatching {
        val util = PhoneNumberUtil.getInstance()
        val example = util.getExampleNumberForType(country.iso, PhoneNumberUtil.PhoneNumberType.MOBILE)
        // The NATIONAL format keeps the domestic trunk prefix (e.g. TR "0501 …"), but the
        // user types the E.164 national significant number without it. Strip the "+<code>"
        // off the INTERNATIONAL format instead so the hint reads "501 …", matching input.
        util.format(example, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            .removePrefix("+${country.dialCode}")
            .trim()
    }.getOrDefault("")

/**
 * Formats the raw national digits as the user types (e.g. TR "5012345678" → "501 234 56 78")
 * by running libphonenumber's as-you-type formatter over the full international number and
 * showing only the part after the dial code. The state keeps raw digits; only the display
 * changes, so E.164 composition and validation stay untouched.
 */
private class PhoneNumberVisualTransformation(
    private val regionIso: String,
    private val dialCode: Int
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        if (digits.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val result = runCatching { formatNationalPart(digits) }.getOrNull()
            ?: return TransformedText(text, OffsetMapping.Identity)
        val (formatted, digitPositions) = result
        if (digitPositions.size != digits.length) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= 0) {
                    0
                } else {
                    ((digitPositions.getOrNull(offset - 1) ?: formatted.lastIndex) + 1)
                        .coerceAtMost(formatted.length)
                }

            override fun transformedToOriginal(offset: Int): Int =
                digitPositions.count { it < offset }.coerceAtMost(digits.length)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }

    /** Returns the formatted national part plus each raw digit's index inside it. */
    private fun formatNationalPart(digits: String): Pair<String, List<Int>> {
        // The input is fed in international form, so the region only serves as a hint —
        // but it must be a real region: "ZZ" makes libphonenumber refuse to format.
        val formatter = PhoneNumberUtil.getInstance().getAsYouTypeFormatter(regionIso)
        var out = ""
        "+$dialCode$digits".forEach { out = formatter.inputDigit(it) }
        val digitIndices = out.indices.filter { out[it].isDigit() }
        val userIndices = digitIndices.drop(dialCode.toString().length)
        val start = userIndices.firstOrNull() ?: return "" to emptyList()
        return out.substring(start) to userIndices.map { it - start }
    }
}

/**
 * Modern single-container phone entry: the flag + dial-code selector, a hairline divider and
 * the number input share one 56dp rounded field, so nothing can misalign. The selector opens
 * a searchable country sheet; the number is formatted as you type for the selected country,
 * with an example-number hint and an animated validity check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneNumberInput(
    country: Country,
    onCountryChange: (Country) -> Unit,
    nationalNumber: String,
    onNationalNumberChange: (String) -> Unit,
    countries: List<Country>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var showSheet by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isValid = remember(country, nationalNumber) { isValidPhoneNumber(country, nationalNumber) }
    val placeholder = remember(country) { exampleNationalNumber(country) }

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.outlineVariant
            isError -> MaterialTheme.colorScheme.error
            isFocused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        label = "phone_input_border"
    )
    val borderWidth = if (enabled && (isFocused || isError)) 2.dp else 1.dp
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val selectorCd = stringResource(
                R.string.phone_country_selector_cd, country.name, country.dialCode
            )
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .clickable(enabled = enabled) { showSheet = true }
                    .semantics { contentDescription = selectorCd }
                    .padding(start = 14.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CountryFlag(country.flagRes, Modifier.size(width = 26.dp, height = 19.dp))
                Text(
                    text = "+${country.dialCode}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            BasicTextField(
                value = nationalNumber,
                onValueChange = { input ->
                    // Typed text can never contain '+' (it's filtered), so a leading '+'
                    // means a pasted international number — strip the dial code so
                    // "+90 501…" doesn't become the digits "90501…".
                    val withoutDialCode = input.trim().let { raw ->
                        if (raw.startsWith("+")) {
                            raw.removePrefix("+").removePrefix(country.dialCode.toString())
                        } else {
                            raw
                        }
                    }
                    onNationalNumberChange(withoutDialCode.filter { it.isDigit() }.take(16))
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction() },
                    onNext = { onImeAction() }
                ),
                visualTransformation = remember(country.iso, country.dialCode) {
                    PhoneNumberVisualTransformation(country.iso, country.dialCode)
                },
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (nationalNumber.isEmpty() && placeholder.isNotBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )

            AnimatedVisibility(
                visible = isValid,
                enter = fadeIn() + scaleIn(initialScale = 0.6f),
                exit = fadeOut() + scaleOut(targetScale = 0.6f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            CountryPickerContent(
                countries = countries,
                selectedIso = country.iso,
                onSelect = {
                    onCountryChange(it)
                    showSheet = false
                }
            )
        }
    }
}

@Composable
private fun CountryPickerContent(
    countries: List<Country>,
    selectedIso: String,
    onSelect: (Country) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, countries) {
        val q = query.trim()
        if (q.isBlank()) {
            countries
        } else {
            val digits = q.filter { it.isDigit() }
            countries.filter { c ->
                c.name.contains(q, ignoreCase = true) ||
                    c.iso.equals(q, ignoreCase = true) ||
                    (digits.isNotBlank() && c.dialCode.toString().startsWith(digits))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.phone_country_picker_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.phone_country_search_hint)) },
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
        ) {
            items(filtered, key = { it.iso }) { country ->
                CountryRow(
                    country = country,
                    isSelected = country.iso == selectedIso,
                    onClick = { onSelect(country) }
                )
            }
        }
    }
}

@Composable
private fun CountryRow(
    country: Country,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CountryFlag(country.flagRes, Modifier.size(width = 34.dp, height = 24.dp))
            Text(
                text = country.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "+${country.dialCode}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
