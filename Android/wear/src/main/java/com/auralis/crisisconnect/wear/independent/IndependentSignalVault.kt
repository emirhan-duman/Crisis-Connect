package com.auralis.crisisconnect.wear.independent

import android.content.Context
import org.json.JSONObject
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.*
import org.signal.libsignal.protocol.kem.*
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import java.util.Base64
import java.util.UUID

/** One encrypted commit contains the ratchet AND ciphertext/plaintext history. No network or replay on restore.
 * Own this vault once per installation; callers must serialize access (including across service instances).
 */
class IndependentSignalVault(context: Context, namespace: String) {
    private val file = IndependentJournalFile(context, "signal-$namespace")
    private var poisoned = false
    init { synchronized(LOCK) {
        if(file.readData()==null) commit(JSONObject().put("version",1).put("identity",b64(IdentityKeyPair.generate().serialize()))
            .put("registration",java.security.SecureRandom().nextInt(16383)+1).put("records",JSONObject())
            .put("outbox",JSONObject()).put("inbox",JSONObject()))
        load()
    } }
    private fun load(): JSONObject {
        check(!poisoned)
        return JSONObject(requireNotNull(file.readData()).toString(Charsets.UTF_8)).also { require(it.getInt("version")==1) }
    }
    @Synchronized fun identityKey(): String = Store(load()).identityKeyPair.publicKey.serialize().let(::b64)
    /** Only use a peer key authenticated by phone-approved device provisioning. Changed keys fail closed. */
    @Synchronized fun pinPeer(uid: String, device: Int, identity: String) = transaction { next, store ->
        val address=address(uid,device);val key=IdentityKey(bytes(identity))
        val old=store.getIdentity(address);require(old==null || old==key){"Peer identity changed; explicit reapproval required"}
        store.saveIdentity(address,key); Unit
    }
    @Synchronized fun publicBundle(device: Int): JSONObject = transaction { next, store ->
        require(device in 2..127)
        if(!store.containsSignedPreKey(1)) {
            val ec=ECKeyPair.generate();val kem=KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            store.storeSignedPreKey(1,SignedPreKeyRecord(1,System.currentTimeMillis(),ec,store.identityKeyPair.privateKey.calculateSignature(ec.publicKey.serialize())))
            store.storeKyberPreKey(1,KyberPreKeyRecord(1,System.currentTimeMillis(),kem,store.identityKeyPair.privateKey.calculateSignature(kem.publicKey.serialize())))
        }
        val ec=store.loadSignedPreKey(1);val kem=store.loadKyberPreKey(1)
        JSONObject().put("registrationId",store.localRegistrationId).put("deviceId",device).put("identityKey",b64(store.identityKeyPair.publicKey.serialize()))
            .put("signedPreKey",b64(ec.keyPair.publicKey.serialize())).put("signedSignature",b64(ec.signature))
            .put("kyberPreKey",b64(kem.keyPair.publicKey.serialize())).put("kyberSignature",b64(kem.signature))
    }
    @Synchronized fun prepare(id: String, uid: String, device: Int, bundle: JSONObject?, clear: ByteArray): JSONObject = transaction { next, store ->
        require(UUID.fromString(id).toString()==id && clear.size in 1..2048)
        val address=address(uid,device); val out=next.getJSONObject("outbox")
        require(out.length()<512 && !out.has(id)){"Message ID already prepared"}
        require(store.getIdentity(address)!=null){"Unapproved peer"}
        if(!store.containsSession(address)) {
            val b=requireNotNull(bundle);require(b.getInt("deviceId")==device)
            require(store.getIdentity(address)==IdentityKey(bytes(b.getString("identityKey"))))
            SessionBuilder(store,address).process(PreKeyBundle(b.getInt("registrationId"),device,-1,null,1,ECPublicKey(bytes(b.getString("signedPreKey"))),bytes(b.getString("signedSignature")),IdentityKey(bytes(b.getString("identityKey"))),1,KEMPublicKey(bytes(b.getString("kyberPreKey"))),bytes(b.getString("kyberSignature"))))
        }
        val message=SessionCipher(store,address).encrypt(clear)
        val wire=JSONObject().put("id",id).put("recipientUid",uid).put("recipientDevice",device)
            .put("ctype",if(message.type==CiphertextMessage.PREKEY_TYPE)"prekey" else "signal")
            .put("ciphertext",b64(message.serialize())).put("state","uncertain")
        // Local text is inside the encrypted archive, never copied into the network envelope.
        out.put(id,JSONObject(wire.toString()).put("clear",b64(clear)))
        wire
    }
    @Synchronized fun receive(id: String, uid: String, device: Int, type: String, ciphertext: String): ByteArray = transaction { next, store ->
        require(UUID.fromString(id).toString()==id && ciphertext.length<=24000)
        val address=address(uid,device);require(store.getIdentity(address)!=null){"Unapproved sender device"}
        val inbox=next.getJSONObject("inbox")
        val fingerprint="$uid/$device/$type/$ciphertext"
        if(inbox.has(id)) { val old=inbox.getJSONObject(id);require(old.getString("wire")==fingerprint);bytes(old.getString("clear")) }
        else {
            require(inbox.length()<512)
            val cipher=SessionCipher(store,address)
            val clear=when(type){"prekey"->cipher.decrypt(PreKeySignalMessage(bytes(ciphertext)));"signal"->cipher.decrypt(SignalMessage(bytes(ciphertext)));else->error("Unknown Signal type")}
            require(clear.size in 1..2048)
            inbox.put(id,JSONObject().put("wire",fingerprint).put("clear",b64(clear)).put("senderUid",uid).put("senderDevice",device).put("receivedAt",System.currentTimeMillis()))
            clear
        }
    }
    @Synchronized fun incoming(): JSONObject = synchronized(LOCK) { JSONObject(load().getJSONObject("inbox").toString()) }
    @Synchronized fun outgoing(): JSONObject = synchronized(LOCK) { JSONObject(load().getJSONObject("outbox").toString()) }
    @Synchronized fun reconcile(id: String, state: String) = transaction { next, _ ->
        val row=next.getJSONObject("outbox").getJSONObject(id)
        val ranks=listOf("uncertain","accepted","delivered","read")
        require(state in ranks.drop(1));if(ranks.indexOf(state)>ranks.indexOf(row.getString("state")))row.put("state",state);Unit
    }
    private fun <T> transaction(block: (JSONObject,Store)->T): T = synchronized(LOCK) {
        check(!poisoned){"Reload after ambiguous persistence"}
        val next=load();val result=block(next,Store(next));commit(next);result
    }
    private fun commit(next: JSONObject) { try { file.writeData(next.toString().toByteArray()) } catch(e:Exception){poisoned=true;throw e} }
    private class Store(private val root: JSONObject): SignalProtocolStore {
        private val records=root.getJSONObject("records")
        private fun get(key:String)=records.optString(key).takeIf{it.isNotEmpty()}?.let(::bytes)
        private fun put(key:String,value:ByteArray){records.put(key,b64(value))}
        override fun getIdentityKeyPair()=IdentityKeyPair(bytes(root.getString("identity")))
        override fun getLocalRegistrationId()=root.getInt("registration")
        override fun getIdentity(a:SignalProtocolAddress)=get("identity:$a")?.let(::IdentityKey)
        override fun isTrustedIdentity(a:SignalProtocolAddress,k:IdentityKey,d:IdentityKeyStore.Direction)=getIdentity(a)==k
        override fun saveIdentity(a:SignalProtocolAddress,k:IdentityKey):IdentityKeyStore.IdentityChange {val old=getIdentity(a);require(old==null||old==k);put("identity:$a",k.serialize());return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED}
        override fun loadSession(a:SignalProtocolAddress)=get("session:$a")?.let(::SessionRecord)
        override fun loadExistingSessions(a:List<SignalProtocolAddress>)=a.map{loadSession(it)?:throw NoSessionException("Missing session")}
        override fun getSubDeviceSessions(name:String)= (2..127).filter{containsSession(SignalProtocolAddress(name,it))}
        override fun storeSession(a:SignalProtocolAddress,r:SessionRecord)=put("session:$a",r.serialize())
        override fun containsSession(a:SignalProtocolAddress)=loadSession(a)?.hasSenderChain()==true
        override fun deleteSession(a:SignalProtocolAddress){records.remove("session:$a")}
        override fun deleteAllSessions(name:String){(1..127).forEach{deleteSession(SignalProtocolAddress(name,it))}}
        override fun loadPreKey(id:Int)=get("pre:$id")?.let(::PreKeyRecord)?:throw InvalidKeyIdException("Missing prekey")
        override fun storePreKey(id:Int,r:PreKeyRecord)=put("pre:$id",r.serialize())
        override fun containsPreKey(id:Int)=records.has("pre:$id")
        override fun removePreKey(id:Int){records.remove("pre:$id")}
        override fun loadSignedPreKey(id:Int)=get("signed:$id")?.let(::SignedPreKeyRecord)?:throw InvalidKeyIdException("Missing signed prekey")
        override fun storeSignedPreKey(id:Int,r:SignedPreKeyRecord)=put("signed:$id",r.serialize())
        override fun containsSignedPreKey(id:Int)=records.has("signed:$id")
        override fun removeSignedPreKey(id:Int){records.remove("signed:$id")}
        override fun loadSignedPreKeys()=records.keys().asSequence().filter{it.startsWith("signed:")}.map{SignedPreKeyRecord(get(it)!!)}.toList()
        override fun loadKyberPreKey(id:Int)=get("kyber:$id")?.let(::KyberPreKeyRecord)?:throw InvalidKeyIdException("Missing Kyber prekey")
        override fun storeKyberPreKey(id:Int,r:KyberPreKeyRecord)=put("kyber:$id",r.serialize())
        override fun containsKyberPreKey(id:Int)=records.has("kyber:$id")
        override fun loadKyberPreKeys()=records.keys().asSequence().filter{it.startsWith("kyber:")}.map{KyberPreKeyRecord(get(it)!!)}.toList()
        override fun markKyberPreKeyUsed(id:Int,signed:Int,base:ECPublicKey){val key="used:$id:$signed:${b64(base.serialize())}";require(!records.has(key));records.put(key,true)}
        override fun storeSenderKey(a:SignalProtocolAddress,id:UUID,r:SenderKeyRecord)=put("sender:$a:$id",r.serialize())
        override fun loadSenderKey(a:SignalProtocolAddress,id:UUID)=get("sender:$a:$id")?.let(::SenderKeyRecord)
    }
    companion object {
        private val LOCK=Any()
        private fun address(uid:String,device:Int):SignalProtocolAddress{require(uid.matches(Regex("[A-Za-z0-9]{1,128}"))&&device in 1..127);return SignalProtocolAddress(uid,device)}
        fun b64(b:ByteArray):String=Base64.getEncoder().encodeToString(b)
        fun bytes(s:String):ByteArray=Base64.getDecoder().decode(s)
    }
}
