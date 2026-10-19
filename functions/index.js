// Notifications de « Nous deux »
// Le contenu est chiffré de bout en bout : les notifications disent seulement
// QUI a fait quoi, jamais ce qui a été écrit.

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { setGlobalOptions } = require("firebase-functions/v2");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const db = getFirestore();
const messaging = getMessaging();

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
  const res = await messaging.sendEachForMulticast({
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
  if (bad.length) await userDoc.ref.update({ fcm: FieldValue.arrayRemove(...bad) });
  return { total: tokens.length, sent: res.successCount, errors };
}

// Prévient l'autre personne
async function notifyOthers(by, makeBody, tag) {
  const users = await db.collection("users").get();
  const sender = users.docs.find((d) => d.id === by);
  const name = (sender && sender.get("name")) || "Ton amour";
  for (const u of users.docs) {
    if (u.id === by) continue;
    await sendTo(u, { title: "Nous deux", body: makeBody(name), tag });
  }
}

exports.notifierMessage = onDocumentCreated("messages/{id}", async (event) => {
  const msg = event.data && event.data.data();
  if (!msg || !msg.by || msg.kind === "call") return; // le résumé d'appel ne déclenche pas « t'a écrit »
  await notifyOthers(msg.by, (n) => `${n} t'a écrit 💌`, "message");
});

exports.notifierEvenement = onDocumentCreated("events/{id}", async (event) => {
  const ev = event.data && event.data.data();
  if (!ev || !ev.by) return;
  await notifyOthers(ev.by, (n) => `${n} a ajouté un événement à l'agenda 📅`, "agenda");
});

exports.notifierAppel = onDocumentCreated("calls/{id}", async (event) => {
  const c = event.data && event.data.data();
  if (!c || !c.by || c.status !== "ringing") return;
  await notifyOthers(c.by, (n) => `📞 ${n} t'appelle${c.video ? " en vidéo" : ""}… Touche pour répondre`, "call");
});

// Bouton « Tester les notifications » : envoie à TES appareils et note le résultat
exports.testerNotification = onDocumentCreated("pings/{id}", async (event) => {
  const p = event.data && event.data.data();
  if (!p || !p.by) return;
  const u = await db.collection("users").doc(p.by).get();
  let result;
  try {
    result = await sendTo(u, { title: "Nous deux", body: "Test réussi 🔔 Les notifications fonctionnent!", tag: "test", force: "1" });
  } catch (e) {
    result = { total: -1, sent: 0, errors: [String(e.code || e.message || e)] };
  }
  await event.data.ref.set({ done: true, ...result }, { merge: true });
});

// Relais d'appels (TURN Cloudflare) : identifiants temporaires, seulement pour vous deux
exports.turnCredentials = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Connexion requise");
  const me = await db.collection("users").doc(uid).get();
  if (!me.exists) throw new HttpsError("permission-denied", "Accès refusé");
  const keyId = process.env.CF_TURN_KEY_ID, token = process.env.CF_TURN_TOKEN;
  if (!keyId || !token) return { iceServers: [], configured: false };
  const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${keyId}/credentials/generate-ice-servers`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ ttl: 86400 }),
  });
  if (!r.ok) throw new HttpsError("unavailable", `Cloudflare a répondu ${r.status}`);
  const j = await r.json();
  const list = Array.isArray(j.iceServers) ? j.iceServers : [j.iceServers];
  // le port 53 ralentit les navigateurs : on l'enlève
  const iceServers = list.filter(Boolean).map((s) => ({ ...s, urls: [].concat(s.urls).filter((u) => !/:53(\?|$)/.test(u)) }));
  return { iceServers, configured: true };
});

// Assistant IA (Claude) : relais vers l'API d'Anthropic (clé : secret GitHub ANTHROPIC_API_KEY).
// L'app envoie seulement ce dont l'assistant a besoin (inventaire, épicerie, agenda à venir).
// Les modifications sont appliquées par l'app elle-même, qui garde le chiffrement.
const ASSISTANT_MODEL = process.env.ASSISTANT_MODEL || "claude-sonnet-5-5";
exports.assistant = onCall({ timeoutSeconds: 120, memory: "256MiB" }, async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Connexion requise");
  const me = await db.collection("users").doc(uid).get();
  if (!me.exists) throw new HttpsError("permission-denied", "Accès refusé");
  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) throw new HttpsError("failed-precondition", "L'assistant n'est pas encore configuré (clé d'API manquante).");
  const { system, messages, tools } = req.data || {};
  if (!Array.isArray(messages) || !messages.length || messages.length > 120) throw new HttpsError("invalid-argument", "Conversation invalide");
  if (JSON.stringify(req.data).length > 300000) throw new HttpsError("invalid-argument", "Conversation trop longue : commence une nouvelle conversation.");
  const r = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model: ASSISTANT_MODEL, max_tokens: 2048, system: String(system || "").slice(0, 60000), messages, tools: Array.isArray(tools) ? tools : undefined }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) {
    console.error("Anthropic", r.status, j && j.error);
    const msg = (j && j.error && j.error.message) || `Erreur ${r.status}`;
    if (r.status === 401) throw new HttpsError("permission-denied", "Clé d'API refusée par Anthropic.");
    if (r.status === 400 && /credit/i.test(msg)) throw new HttpsError("resource-exhausted", "Plus de crédits sur le compte Anthropic.");
    if (r.status === 429 || r.status === 529) throw new HttpsError("resource-exhausted", "L'assistant est surchargé, réessaie dans un instant.");
    throw new HttpsError("unavailable", msg);
  }
  return { content: j.content, stop_reason: j.stop_reason, usage: j.usage, model: j.model };
});
