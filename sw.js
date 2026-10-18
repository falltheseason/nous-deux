// Service worker : installation, ouverture rapide hors ligne et notifications.
// Toujours « réseau d'abord » pour que les mises à jour arrivent tout de suite.
const CACHE = "nous-deux-v5";
const SHELL = ["./", "./index.html", "./manifest.json", "./icon-192.png", "./icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});
self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
        return res;
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match("./index.html")))
  );
});

// Notifications (envoyées par Firebase Cloud Messaging)
self.addEventListener("push", (e) => {
  let p = {};
  try { p = e.data ? e.data.json() : {}; } catch { p = {}; }
  const d = p.data || p.notification || p;
  e.waitUntil((async () => {
    // Si tu regardes déjà l'app sur cet appareil, pas de son (l'app fait son propre « bing »)
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const looking = d.force !== "1" && wins.some((c) => c.visibilityState === "visible" && c.focused);
    await self.registration.showNotification(d.title || "Nous deux", {
      body: d.body || "Nouveau message 💌",
      icon: "icon-192.png",
      badge: "icon-192.png",
      tag: d.tag || "nous-deux",
      renotify: !looking,
      silent: looking,
      vibrate: looking ? undefined : d.tag === "call" ? [600, 300, 600, 300, 600, 300, 600] : [120, 60, 120],
      requireInteraction: d.tag === "call" && !looking,
      data: { url: "./" },
    });
    if (looking) {
      const shown = await self.registration.getNotifications({ tag: d.tag || "nous-deux" });
      shown.forEach((n) => n.close());
    }
  })());
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const target = new URL(e.notification.data?.url || "./", self.registration.scope).href;
  e.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      for (const c of list) if (c.url.startsWith(self.registration.scope) && "focus" in c) return c.focus();
      return self.clients.openWindow(target);
    })
  );
});
