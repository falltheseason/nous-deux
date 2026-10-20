// Test réel de l'assistant : 2 allers-retours avec un outil, comme le fait l'app.
// Coût : une fraction de cent.
const key = process.env.AI_KEY;
const model = process.env.ASSISTANT_MODEL || "claude-sonnet-5-5";
const system = "Tu es l'assistant d'inventaire d'une app de couple. Réponds en français, très brièvement. Chaque message de l'usager commence par un bloc <contexte> à jour.";
const tools = [{ name: "add_pantry_items", description: "Ajoute des articles à l'inventaire.",
  input_schema: { type: "object", required: ["items"], properties: { items: { type: "array", items: { type: "object", required: ["name", "qty", "loc"],
    properties: { name: { type: "string" }, qty: { type: "number" }, unit: { type: "string" }, loc: { type: "string" } } } } } } }];
async function call(messages) {
  const r = await fetch("https://api.anthropic.com/v1/messages", { method: "POST",
    headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model, max_tokens: 1024, system, messages, tools }) });
  const j = await r.json();
  if (!r.ok) throw new Error(`HTTP ${r.status} : ${j.error?.message}`);
  return j;
}
const messages = [{ role: "user", content: [{ type: "text", text: "<contexte>\nEMPLACEMENTS : frigo, congelo, garde\nINVENTAIRE : (vide)\n</contexte>\n\n5 oignons moyens" }] }];
try {
  const a = await call(messages);
  console.log("1er appel :", a.stop_reason, "| blocs :", a.content.map((b) => b.type).join(","));
  messages.push({ role: "assistant", content: a.content });
  const uses = a.content.filter((b) => b.type === "tool_use");
  if (uses.length) {
    messages.push({ role: "user", content: uses.map((u) => ({ type: "tool_result", tool_use_id: u.id, content: JSON.stringify({ ok: true, items: [{ id: "x1", name: "Oignons moyens", qty: 5 }] }) })) });
    const b = await call(messages);
    console.log("2e appel :", b.stop_reason, "|", (b.content.find((x) => x.type === "text")?.text || "").slice(0, 120));
  }
  console.log("ASSISTANT OK");
} catch (e) { console.log("ASSISTANT ÉCHEC :", e.message); }
