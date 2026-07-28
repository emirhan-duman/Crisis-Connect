import { getAuth } from "firebase-admin/auth";
import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as https from "https";

/**
 * Authenticated bridge for Cloudflare Realtime TURN credentials.
 *
 * The Android app calls `/api/turn-credentials` with a Firebase ID token. This function verifies the
 * caller and uses the server-held Cloudflare TURN key/API token to mint short-lived ICE servers.
 * Cloudflare documents this API at:
 * https://developers.cloudflare.com/realtime/turn/generate-credentials/
 */

const cloudflareTurnKeyId = defineSecret("CLOUDFLARE_TURN_KEY_ID");
const cloudflareTurnApiToken = defineSecret("CLOUDFLARE_TURN_API_TOKEN");

const TURN_TTL_SECONDS = 3600;
const MAX_RESPONSE_BYTES = 64 * 1024;

interface IceServer {
  urls: string | string[];
  username?: string;
  credential?: string;
}

interface TurnResponse {
  iceServers: IceServer[];
  ttl: number;
}

export const turnCredentials = onRequest(
  {
    region: "us-central1",
    secrets: [cloudflareTurnKeyId, cloudflareTurnApiToken],
  },
  async (request, response) => {
    response.set("Cache-Control", "no-store");
    response.set("Vary", "Authorization");

    if (request.method !== "GET") {
      response.set("Allow", "GET");
      response.status(405).json({ error: "method_not_allowed" });
      return;
    }

    const idToken = parseBearerToken(request.header("authorization"));
    if (!idToken) {
      response.status(401).json({ error: "missing_authorization" });
      return;
    }

    const decoded = await getAuth().verifyIdToken(idToken).catch(() => null);
    if (!decoded?.uid) {
      response.status(401).json({ error: "invalid_authorization" });
      return;
    }

    const keyId = cloudflareTurnKeyId.value().trim();
    const apiToken = cloudflareTurnApiToken.value().trim();
    if (!keyId || !apiToken) {
      console.error("Cloudflare TURN credentials are not configured.");
      response.status(503).json({ error: "turn_not_configured" });
      return;
    }

    try {
      const cloudflare = await postJson(
        `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(
          keyId
        )}/credentials/generate-ice-servers`,
        apiToken,
        { ttl: TURN_TTL_SECONDS }
      );
      const body = normalizeTurnResponse(cloudflare);
      if (body.iceServers.length === 0) {
        response.status(502).json({ error: "empty_turn_response" });
        return;
      }
      response.status(200).json(body);
    } catch (error) {
      console.error("Failed to mint Cloudflare TURN credentials", error);
      response.status(502).json({ error: "turn_provider_failed" });
    }
  }
);

function parseBearerToken(header: string | undefined): string | null {
  const match = /^Bearer\s+(.+)$/i.exec(header?.trim() ?? "");
  return match?.[1]?.trim() || null;
}

function normalizeTurnResponse(raw: unknown): TurnResponse {
  const record = raw as { iceServers?: unknown };
  const servers = Array.isArray(record?.iceServers) ? record.iceServers : [];
  return {
    ttl: TURN_TTL_SECONDS,
    iceServers: servers.map(normalizeIceServer).filter((server): server is IceServer => server !== null),
  };
}

function normalizeIceServer(raw: unknown): IceServer | null {
  const record = raw as { urls?: unknown; username?: unknown; credential?: unknown };
  const urls = normalizeUrls(record?.urls);
  if (urls.length === 0) return null;
  const server: IceServer = { urls };
  if (typeof record.username === "string" && record.username.trim()) {
    server.username = record.username.trim();
  }
  if (typeof record.credential === "string" && record.credential.trim()) {
    server.credential = record.credential.trim();
  }
  return server;
}

function normalizeUrls(raw: unknown): string[] {
  const values = Array.isArray(raw) ? raw : typeof raw === "string" ? [raw] : [];
  return values
    .filter((value): value is string => typeof value === "string")
    .map((value) => value.trim())
    .filter((value) => /^(stun|turn|turns):/i.test(value));
}

function postJson(url: string, bearerToken: string, body: unknown): Promise<unknown> {
  const payload = Buffer.from(JSON.stringify(body), "utf8");
  return new Promise((resolve, reject) => {
    const req = https.request(
      url,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${bearerToken}`,
          "Content-Type": "application/json",
          "Content-Length": payload.length.toString(),
        },
        timeout: 8000,
      },
      (res) => {
        const chunks: Buffer[] = [];
        let totalBytes = 0;
        res.on("data", (chunk: Buffer) => {
          totalBytes += chunk.length;
          if (totalBytes > MAX_RESPONSE_BYTES) {
            req.destroy(new Error("TURN provider response too large"));
            return;
          }
          chunks.push(chunk);
        });
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          if (!res.statusCode || res.statusCode < 200 || res.statusCode >= 300) {
            reject(new Error(`TURN provider HTTP ${res.statusCode}: ${text.slice(0, 200)}`));
            return;
          }
          try {
            resolve(JSON.parse(text));
          } catch (error) {
            reject(error);
          }
        });
      }
    );
    req.on("timeout", () => req.destroy(new Error("TURN provider timeout")));
    req.on("error", reject);
    req.end(payload);
  });
}
