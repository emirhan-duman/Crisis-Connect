const featureGroups = [
  {
    title: "Field Communication",
    items: [
      "BLE and GATT mesh messaging",
      "RFCOMM call flows",
      "Voice, image, and file transfer",
    ],
  },
  {
    title: "Operations",
    items: [
      "Rescue-mode scanning",
      "Crisis Link sync profiles",
      "Offline map region support",
    ],
  },
  {
    title: "Security",
    items: [
      "Android Keystore identity",
      "Role certificates via Firebase Functions",
      "App Check and encrypted local storage",
    ],
  },
];

const quickChecks = [
  "./gradlew app:testInternalUnitTest",
  "./gradlew feature_rescue:testDebugUnitTest",
  "cd functions && npm run build",
];

export default function DashboardPage() {
  return (
    <main
      style={{
        minHeight: "100vh",
        background:
          "radial-gradient(circle at top, #17324d 0%, #0f1724 45%, #081019 100%)",
        color: "#f3f7fb",
        fontFamily: "ui-sans-serif, system-ui, sans-serif",
        padding: "48px 24px 72px",
      }}
    >
      <div style={{ maxWidth: 1040, margin: "0 auto" }}>
        <section
          style={{
            border: "1px solid rgba(255,255,255,0.12)",
            borderRadius: 28,
            padding: 32,
            background: "rgba(9, 17, 27, 0.72)",
            boxShadow: "0 24px 80px rgba(0,0,0,0.28)",
          }}
        >
          <p
            style={{
              margin: 0,
              letterSpacing: "0.12em",
              textTransform: "uppercase",
              color: "#7dd3fc",
              fontSize: 12,
            }}
          >
            Disaster Communication System
          </p>
          <h1 style={{ margin: "12px 0 16px", fontSize: 48, lineHeight: 1.05 }}>
            Crisis-ready communications stack for low-connectivity environments
          </h1>
          <p
            style={{
              margin: 0,
              maxWidth: 760,
              color: "rgba(243,247,251,0.78)",
              fontSize: 18,
              lineHeight: 1.6,
            }}
          >
            This surface summarizes the Android app, rescue feature, and backend support
            layers that power nearby communication, rescue coordination, and secure role-based
            operation during degraded-network scenarios.
          </p>
        </section>

        <section
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))",
            gap: 20,
            marginTop: 24,
          }}
        >
          {featureGroups.map((group) => (
            <article
              key={group.title}
              style={{
                borderRadius: 24,
                padding: 24,
                background: "rgba(255,255,255,0.06)",
                border: "1px solid rgba(255,255,255,0.08)",
              }}
            >
              <h2 style={{ marginTop: 0, marginBottom: 16, fontSize: 22 }}>{group.title}</h2>
              <ul style={{ margin: 0, paddingLeft: 18, color: "rgba(243,247,251,0.78)" }}>
                {group.items.map((item) => (
                  <li key={item} style={{ marginBottom: 10 }}>
                    {item}
                  </li>
                ))}
              </ul>
            </article>
          ))}
        </section>

        <section
          style={{
            marginTop: 24,
            borderRadius: 24,
            padding: 24,
            background: "rgba(255,255,255,0.05)",
            border: "1px solid rgba(255,255,255,0.08)",
          }}
        >
          <h2 style={{ marginTop: 0, fontSize: 24 }}>Verification</h2>
          <p style={{ color: "rgba(243,247,251,0.72)", lineHeight: 1.6 }}>
            Keep these checks green before cutting rescue or mesh releases.
          </p>
          <div style={{ display: "grid", gap: 12 }}>
            {quickChecks.map((command) => (
              <code
                key={command}
                style={{
                  display: "block",
                  padding: "14px 16px",
                  borderRadius: 16,
                  background: "rgba(8, 16, 25, 0.85)",
                  border: "1px solid rgba(125, 211, 252, 0.18)",
                  color: "#bae6fd",
                }}
              >
                {command}
              </code>
            ))}
          </div>
        </section>
      </div>
    </main>
  );
}
