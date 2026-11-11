import { HttpsError } from "firebase-functions/v2/https";
import { getFirestore, Transaction } from "firebase-admin/firestore";
import { base64, watchID, WatchRegistration, WatchRequest } from "./deviceProtocol";
import { PreparedWatchEffect } from "./commandJournal";

/** Separate device-addressed Signal inbox. Never overwrites legacy phone device-1 sessions. */
export async function prepareWatchMessage(tx: Transaction, sender: WatchRegistration, request: WatchRequest): Promise<PreparedWatchEffect> {
  const db=getFirestore(), body=request.body, recipientID=watchID(body.recipientInstallation);
  const [recipientDoc,approved,reciprocal,senderKeys,recipientKeys]=await tx.getAll(
    db.doc(`watchInstallations/${recipientID}`),db.doc(`watchInstallations/${sender.deviceID}/contacts/${recipientID}`),
    db.doc(`watchInstallations/${recipientID}/contacts/${sender.deviceID}`),db.doc(`watchInstallations/${sender.deviceID}/signal/binding`),db.doc(`watchInstallations/${recipientID}/signal/binding`));
  const recipient=recipientDoc.data() as WatchRegistration|undefined;
  if(!recipient || recipient.deviceID===sender.deviceID || recipient.ownerUid===sender.ownerUid || recipient.revokedAt!==null || recipient.expiresAt<=Date.now() ||
      !senderKeys.exists || !recipientKeys.exists || approved.get("enabled")!==true || reciprocal.get("enabled")!==true ||
      approved.get("identityKey")!==recipientKeys.get("identityKey") || reciprocal.get("identityKey")!==senderKeys.get("identityKey"))
    throw new HttpsError("permission-denied","Peer device is not approved.");
  if(body.recipientUid!==recipient.ownerUid || body.recipientDevice!==recipientKeys.get("deviceId") || body.messageID!==request.id || !["prekey","signal"].includes(String(body.ctype)))
    throw new HttpsError("invalid-argument","Invalid Signal route.");
  base64(body.ciphertext, 16_000);
  const inbox=db.doc(`watchInstallations/${recipientID}/inbox/${request.id}`);
  if((await tx.get(inbox)).exists)throw new HttpsError("already-exists","Message ID already used.");
  const message={id:request.id,senderUid:sender.ownerUid,senderInstallation:sender.deviceID,senderDevice:senderKeys.get("deviceId"),
    recipientUid:recipient.ownerUid,recipientInstallation:recipientID,recipientDevice:recipientKeys.get("deviceId"),
    ctype:body.ctype,ciphertext:body.ciphertext,createdAt:Date.now(),state:"accepted"};
  return {outcome:"accepted",details:{messageID:request.id,recipientInstallation:recipientID},write:()=>tx.create(inbox,message)};
}

/** Recipient reports delivery only after committing plaintext together with the receiving ratchet. */
export async function acknowledgeWatchMessage(tx: Transaction, recipient: WatchRegistration, request: WatchRequest): Promise<PreparedWatchEffect> {
  const id=watchID(request.body.messageID),db=getFirestore(),inbox=db.doc(`watchInstallations/${recipient.deviceID}/inbox/${id}`);
  const message=await tx.get(inbox);
  if(!message.exists || message.get("recipientUid")!==recipient.ownerUid)throw new HttpsError("permission-denied","Unknown message.");
  const receipt=db.doc(`watchInstallations/${watchID(message.get("senderInstallation"))}/commands/${id}`),sent=await tx.get(receipt);
  if(!sent.exists)throw new HttpsError("failed-precondition","Missing send receipt.");
  return {outcome:"accepted",details:{messageID:id},write:()=>{tx.update(inbox,{state:"delivered"});if(sent.get("outcome")!=="read")tx.update(receipt,{outcome:"delivered"});}};
}
