import { initializeApp } from "firebase-admin/app";

initializeApp();

export { requestAttestationChallenge } from "./certificates/challenges";
export { issueRoleCertificate } from "./certificates/issuance";
export { revokeRoleCertificate } from "./certificates/revocation";
export { listCertificates, validateCertificate } from "./certificates/listing";
export { issueAuthorityMeshKey } from "./certificates/authorityMeshKey";
export {
  getCrisisSentinelModelDownloadUrl,
  getCrisisSentinelModelManifest,
} from "./crisisSentinel/modelManifest";
export { publishIdentityKey, lookupIdentityKey } from "./messaging/identityKeys";
export { findContactsOnCrisisConnect } from "./messaging/contactDiscovery";
export { registerPushToken, unregisterPushToken } from "./messaging/pushTokens";
export { relayMessage } from "./messaging/relay";
export { onMessageCreated } from "./messaging/onMessageCreated";
export {
  onAgencyAuthorityCallCreated,
  onHierarchyAuthorityCallCreated,
} from "./messaging/onAuthorityCallCreated";
export { onAgencyMessageCreated, onHierarchyMessageCreated } from "./messaging/onChannelMessageCreated";
export { acknowledgeMessage } from "./messaging/messageAck";
export { compactInbox } from "./messaging/compactInbox";
export { messagingRegionProbe, messagingRegionProbeEurope, messagingRegionProbeMumbai } from "./messaging/regionProbe";
export { messagingGatewayEurope, messagingGatewayUS, messagingGatewayMumbai } from "./messaging/regionalGateway";
export { messagingGatewayHaEurope, messagingGatewayHaUS } from "./messaging/highAvailabilityGateway";
export {
  onHaMessageCreatedEurope, onHaMessageCreatedUS, onHaMessageDeletedEurope, onHaMessageDeletedUS,
  onLegacyMessageForHa, onLegacyMessageDeletedForHa, onHaInboxMigration, repairHaDelivery,
} from "./messaging/highAvailabilityDelivery";
export { onHaPushTokenChanged } from "./messaging/highAvailabilityTokens";
export { onLegacyProfileChanged, onHaProfileChangedEurope, onHaProfileChangedUS } from "./messaging/resilientProfiles";
export { onLegacyAgencyMetadataChanged, onLegacyHierarchyMetadataChanged, onLegacyMessagingPolicyChanged } from "./messaging/resilientGroups";
export { onHaGroupMessageEurope, onHaGroupMessageUS } from "./messaging/resilientGroupDelivery";
export { applicationMediaEurope, applicationMediaUS, onLegacyProfileMediaFinalized, onLegacyProfileMediaDeleted } from "./messaging/resilientMedia";
export { onHaApplicationAccountDeletedEurope, onHaApplicationAccountDeletedUS } from "./messaging/resilientErasure";
export { purgeExpiredMessages } from "./messaging/purgeExpiredMessages";
export { issueAgencyMessagingKey } from "./messaging/agencyKey";
export { listAuthorityRoster } from "./messaging/authorityRoster";
export { turnCredentials } from "./messaging/turnCredentials";
export { requestPhoneOtp, verifyPhoneOtp, twilioSpendAlert } from "./messaging/phoneOtp";
export { otpConversionAlarm } from "./messaging/otpConversionAlarm";
export { publishSignalPreKeys, checkSignalPreKeys, fetchSignalPreKeyBundle } from "./messaging/signalPreKeys";
export { requestAuthorityMlsPreparation } from "./messaging/authorityMlsPreparation";
export { reportSosSignal } from "./sos/reportSignal";
export { deleteAccountAndData } from "./account/deleteAccount";

// Independent watch/phone chat enrollment stays unavailable until its explicit rollout flags are enabled.
export { enrollIndependentWatch, revokeIndependentWatch } from "./watch/deviceRegistry";
export { configureIndependentWatchSignal, approveIndependentWatchContact } from "./watch/signalProvisioning";
export { listIndependentContactDevices, revokeIndependentWatchContact, readIndependentWatchReceipts } from "./watch/phoneContacts";
export { independentWatchGateway } from "./watch/gateway";
