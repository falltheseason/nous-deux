// Notifications de « Nous deux »
// Quand un message est créé, on prévient l'autre personne sur ses appareils.
// Le contenu des messages est chiffré de bout en bout : la notification
// dit seulement QUI a écrit, jamais ce qui a été écrit.

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();

// La région doit correspondre à celle de la base Firestore (fournie au déploiement).
setGlobalOptions({ region: process.env.ND_REGION || "northamerica-northeast1", maxInstances: 2 });

const PRESENT_MS = 75 * 1000; // l'autre a l'app ouverte → pas de notification
const BAD_TOKEN = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

exports.notifierMessage = onDocumentCreated("messages/{id}", async (event) => {
  const msg = event.data && event.data.data();
  if (!msg || !msg.by) return;

  const db = admin.firestore();
  const users = await db.collection("users").get();
  const sender = users.docs.find((d) => d.id === msg.by);
  const name = (sender && sender.get("name")) || "Ton amour";
  const now = Date.now();

  for (const u of users.docs) {
    if (u.id === msg.by) continue;
    const present = u.get("present");
    if (present && typeof present.toMillis === "function" && now - present.toMillis() < PRESENT_MS) continue;
    const tokens = (u.get("fcm") || []).filter(Boolean);
    if (!tokens.length) continue;

    const res = await admin.messaging().sendEachForMulticast({
      tokens,
      data: { title: "Nous deux", body: `${name} t'a écrit 💌`, tag: "message" },
      webpush: { headers: { Urgency: "high", TTL: "86400" } },
    });

    const bad = [];
    res.responses.forEach((r, i) => {
      if (!r.success && r.error && BAD_TOKEN.has(r.error.code)) bad.push(tokens[i]);
    });
    if (bad.length) await u.ref.update({ fcm: admin.firestore.FieldValue.arrayRemove(...bad) });
  }
});
