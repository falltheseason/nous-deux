// Notifications de « Nous deux »
// Le contenu est chiffré de bout en bout : les notifications disent seulement
// QUI a fait quoi, jamais ce qui a été écrit.

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();

// La région doit correspondre à celle de la base Firestore (fournie au déploiement).
setGlobalOptions({ region: process.env.ND_REGION || "northamerica-northeast1", maxInstances: 2 });

const BAD_TOKEN = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

// Envoie à tous les appareils d'une personne, retire les jetons périmés
async function sendTo(userDoc, data) {
  const tokens = (userDoc.get("fcm") || []).filter(Boolean);
  if (!tokens.length) return { total: 0, sent: 0, errors: [] };
  const res = await admin.messaging().sendEachForMulticast({
    tokens,
    data,
    webpush: { headers: { Urgency: "high", TTL: "86400" } },
  });
  const bad = [], errors = [];
  res.responses.forEach((r, i) => {
    if (r.success) return;
    const code = (r.error && r.error.code) || "inconnu";
    errors.push(code);
    if (BAD_TOKEN.has(code)) bad.push(tokens[i]);
  });
  if (bad.length) await userDoc.ref.update({ fcm: admin.firestore.FieldValue.arrayRemove(...bad) });
  return { total: tokens.length, sent: res.successCount, errors };
}

// Prévient l'autre personne
async function notifyOthers(by, makeBody, tag) {
  const users = await admin.firestore().collection("users").get();
  const sender = users.docs.find((d) => d.id === by);
  const name = (sender && sender.get("name")) || "Ton amour";
  for (const u of users.docs) {
    if (u.id === by) continue;
    await sendTo(u, { title: "Nous deux", body: makeBody(name), tag });
  }
}

exports.notifierMessage = onDocumentCreated("messages/{id}", async (event) => {
  const msg = event.data && event.data.data();
  if (!msg || !msg.by) return;
  await notifyOthers(msg.by, (n) => `${n} t'a écrit 💌`, "message");
});

exports.notifierEvenement = onDocumentCreated("events/{id}", async (event) => {
  const ev = event.data && event.data.data();
  if (!ev || !ev.by) return;
  await notifyOthers(ev.by, (n) => `${n} a ajouté un événement à l'agenda 📅`, "agenda");
});

// Bouton « Tester les notifications » : envoie à TES appareils et note le résultat
exports.testerNotification = onDocumentCreated("pings/{id}", async (event) => {
  const p = event.data && event.data.data();
  if (!p || !p.by) return;
  const u = await admin.firestore().collection("users").doc(p.by).get();
  let result;
  try {
    result = await sendTo(u, { title: "Nous deux", body: "Test réussi 🔔 Les notifications fonctionnent!", tag: "test", force: "1" });
  } catch (e) {
    result = { total: -1, sent: 0, errors: [String(e.code || e.message || e)] };
  }
  await event.data.ref.set({ done: true, ...result }, { merge: true });
});
