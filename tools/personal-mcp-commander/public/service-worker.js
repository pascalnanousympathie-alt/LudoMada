const CACHE="ludomada-commander-v1";
self.addEventListener("install",event=>{
  event.waitUntil(caches.open(CACHE).then(c=>c.addAll(["/","/manifest.webmanifest","/commander-icon.svg"])));
  self.skipWaiting();
});
self.addEventListener("activate",event=>event.waitUntil(self.clients.claim()));
self.addEventListener("fetch",event=>{
  const u=new URL(event.request.url);
  if(u.origin===self.location.origin && event.request.method==="GET" && !u.pathname.startsWith("/api/")){
    event.respondWith(fetch(event.request).catch(()=>caches.match(event.request).then(r=>r||caches.match("/"))));
  }
});
