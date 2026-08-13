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
