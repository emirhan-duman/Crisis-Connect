import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { requireUid, requireNonAnonymous } from "../certificates/callerRole";
import { base64, object, verifyWatchSignature, watchID } from "./deviceProtocol";

/** The authenticated phone approves a separate watch Signal identity; phone keys are never copied. */
export const configureIndependentWatchSignal = onCall({ region: "europe-west1", enforceAppCheck: true }, async request => {
  requireNonAnonymous(request);
  const uid=requireUid(request),data=object(request.data);
  if(process.env.WATCH_INDEPENDENT_PROVISIONING!=="true" || data.confirmed!==true)throw new HttpsError("failed-precondition","Explicit phone approval is required.");
  const encoded=base64(data.payload,8192),proof=object(JSON.parse(encoded.toString("utf8"))),id=watchID(proof.deviceID),bundle=object(proof.bundle);
  if(proof.version!==1 || proof.kind!=="signal-binding" || proof.ownerUid!==uid || !Number.isSafeInteger(proof.expiresAt) ||
     Number(proof.expiresAt)<=Date.now() || Number(proof.expiresAt)>Date.now()+600_000)throw new HttpsError("invalid-argument","Invalid binding proof.");
  if(!Number.isInteger(bundle.registrationId) || Number(bundle.registrationId)<1 || Number(bundle.registrationId)>16383 ||
      !Number.isInteger(bundle.deviceId) || Number(bundle.deviceId)<2 || Number(bundle.deviceId)>127)throw new HttpsError("invalid-argument","Invalid Signal identifiers.");
  for(const key of ["identityKey","signedPreKey"]){const bytes=base64(bundle[key],44);if(bytes.length!==33 || bytes[0]!==5)throw new HttpsError("invalid-argument","Invalid Curve25519 public key.");}
  for(const key of ["signedSignature","kyberSignature"])if(base64(bundle[key],88).length!==64)throw new HttpsError("invalid-argument","Invalid Signal signature.");
  const kyber=base64(bundle.kyberPreKey,2200);if(kyber.length<1500 || kyber.length>1600)throw new HttpsError("invalid-argument","Invalid Kyber public key.");
  const binding={registrationId:bundle.registrationId,deviceId:bundle.deviceId,identityKey:bundle.identityKey,signedPreKey:bundle.signedPreKey,
    signedSignature:bundle.signedSignature,kyberPreKey:bundle.kyberPreKey,kyberSignature:bundle.kyberSignature};
  const db=getFirestore(),device=db.doc(`watchInstallations/${id}`),ref=device.collection("signal").doc("binding");
  return db.runTransaction(async tx=>{
    const [installation,old]=await tx.getAll(device,ref);
    if(installation.get("ownerUid")!==uid || installation.get("revokedAt")!==null || installation.get("expiresAt")<=Date.now() || installation.get("signalDeviceId")!==bundle.deviceId)
      throw new HttpsError("permission-denied","Watch is unavailable.");
    verifyWatchSignature(installation.get("publicKey"),data.payload as string,data.signature);
    if(old.exists){for(const [key,value]of Object.entries(binding))if(old.get(key)!==value)throw new HttpsError("failed-precondition","Existing Signal binding cannot be replaced.");}
    else tx.create(ref,binding);
    return {deviceID:id,deviceId:bundle.deviceId,configured:true};
  });
});

/** Caller must select a saved Crisis Connect contact and confirm its device identity on the phone.
 * Reciprocal approval is required at send time; this alone grants no inbound access.
 */
export const approveIndependentWatchContact = onCall({ region: "europe-west1", enforceAppCheck: true }, async request => {
  requireNonAnonymous(request);
  const uid=requireUid(request),data=object(request.data),deviceID=watchID(data.deviceID),peerID=watchID(data.peerInstallation);
  if(process.env.WATCH_INDEPENDENT_PROVISIONING!=="true" || data.confirmed!==true)throw new HttpsError("failed-precondition","Explicit contact approval is required.");
  const db=getFirestore(),own=db.doc(`watchInstallations/${deviceID}`),peer=db.doc(`watchInstallations/${peerID}`);
  return db.runTransaction(async tx=>{
    const [a,b,keys]=await tx.getAll(own,peer,peer.collection("signal").doc("binding"));
    if(a.get("ownerUid")!==uid || a.get("revokedAt")!==null || a.get("expiresAt")<=Date.now() ||
      !b.exists || b.get("ownerUid")===uid || b.get("ownerUid")!==data.peerUid || b.get("revokedAt")!==null || b.get("expiresAt")<=Date.now() || !keys.exists || keys.get("identityKey")!==data.identityKey)
      throw new HttpsError("permission-denied","Contact identity is unavailable.");
    tx.set(own.collection("contacts").doc(peerID),{enabled:true,ownerUid:b.get("ownerUid"),identityKey:keys.get("identityKey"),approvedAt:Date.now(),name:typeof data.name==="string"?data.name.slice(0,60):""});
    return {approved:true,peerInstallation:peerID};
  });
});
