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
