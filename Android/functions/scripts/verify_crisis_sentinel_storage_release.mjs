#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { initializeApp, applicationDefault } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

const DEFAULT_PROJECT_ID = "crisis-connect-1";
const DEFAULT_PLATFORM = "mobile";
const MODEL_OBJECT_PREFIX = "crisis-sentinel/models/";
const APP_CHECK_STORAGE_SERVICE = "firebasestorage.googleapis.com";

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const raw = argv[i];
    if (!raw.startsWith("--")) throw new Error(`Unexpected argument: ${raw}`);
    const key = raw.slice(2);
    const value = argv[i + 1];
    if (!value || value.startsWith("--")) throw new Error(`Missing value for --${key}`);
    args[key] = value;
    i += 1;
  }
  return args;
}

function gcloud(args) {
  return execFileSync("gcloud", args, { encoding: "utf8" }).trim();
}

function requireString(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${label} is missing.`);
  }
  return value.trim();
}

async function readAppCheckStorageService(projectId, projectNumber) {
  const accessToken = gcloud(["auth", "print-access-token"]);
  const response = await fetch(
    `https://firebaseappcheck.googleapis.com/v1beta/projects/${projectNumber}/services/${APP_CHECK_STORAGE_SERVICE}`,
    {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "x-goog-user-project": projectId,
      },
    },
  );
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`App Check service read failed: ${JSON.stringify(body)}`);
  }
  return body;
}

function publicGcsUrl(bucket, objectPath) {
  return `https://storage.googleapis.com/${encodeURIComponent(bucket)}/${objectPath
    .split("/")
    .map((part) => encodeURIComponent(part))
    .join("/")}`;
}

async function verifyDirectPublicReadDenied(bucket, objectPath) {
  const response = await fetch(publicGcsUrl(bucket, objectPath), {
    method: "HEAD",
    redirect: "manual",
  });
  if (response.status >= 200 && response.status < 300) {
    throw new Error(`Direct public GCS read unexpectedly returned HTTP ${response.status}.`);
  }
  return response.status;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const projectId = String(args.project ?? process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT ?? DEFAULT_PROJECT_ID).trim();
  const platform = String(args.platform ?? DEFAULT_PLATFORM).trim();
  const projectNumber = String(args["project-number"] ?? gcloud([
    "projects",
    "describe",
    projectId,
    "--format=value(projectNumber)",
  ])).trim();

  const app = initializeApp({
    projectId,
    credential: applicationDefault(),
  });

  const releasePath = `crisisSentinelModelReleases/${platform}`;
  const releaseSnap = await getFirestore(app).doc(releasePath).get();
  if (!releaseSnap.exists) throw new Error(`${releasePath} does not exist.`);
  const release = releaseSnap.data();

  if (release.enabled !== true) throw new Error(`${releasePath} is not enabled.`);
  if (release.downloadUrl !== null && release.downloadUrl !== undefined) {
    throw new Error(`${releasePath}.downloadUrl must be null/absent for tokenless delivery.`);
  }

  const storageBucket = requireString(release.storageBucket, `${releasePath}.storageBucket`);
  const storagePath = requireString(release.storagePath, `${releasePath}.storagePath`);
  if (!storagePath.startsWith(MODEL_OBJECT_PREFIX)) {
    throw new Error(`${releasePath}.storagePath must start with ${MODEL_OBJECT_PREFIX}.`);
  }

  const file = getStorage(app).bucket(storageBucket).file(storagePath);
  const [exists] = await file.exists();
  if (!exists) throw new Error(`Storage object does not exist: gs://${storageBucket}/${storagePath}`);

  const [metadata] = await file.getMetadata();
  const publicToken = metadata.metadata?.firebaseStorageDownloadTokens;
  if (publicToken) {
    throw new Error(`Storage object still has firebaseStorageDownloadTokens metadata.`);
  }

  const service = await readAppCheckStorageService(projectId, projectNumber);
  if (service.enforcementMode !== "ENFORCED") {
    throw new Error(`${APP_CHECK_STORAGE_SERVICE} App Check enforcement is ${service.enforcementMode ?? "unknown"}.`);
  }
  const directPublicHeadStatus = await verifyDirectPublicReadDenied(storageBucket, storagePath);

  process.stdout.write(`${JSON.stringify({
    ok: true,
    releasePath,
    releaseId: release.id ?? null,
    storageObject: `gs://${storageBucket}/${storagePath}`,
    downloadUrlIsNull: release.downloadUrl === null || release.downloadUrl === undefined,
    publicDownloadTokenPresent: false,
    appCheckStorageEnforcement: service.enforcementMode,
    directPublicHeadStatus,
  }, null, 2)}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
