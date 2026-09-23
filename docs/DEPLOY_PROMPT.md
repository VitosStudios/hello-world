# Deploy-Prompt für Cowork / Copilot / andere Agenten

Den Block unten komplett in einen Agenten kopieren, der per SSH auf deinen
Server kommt. Die Zugangsdaten gibst du dem Agenten **separat** (nie in das
Repo schreiben).

---

```text
Aufgabe: Installiere den "Media Studio"-Server aus dem GitHub-Repo
VitosStudios/hello-world (Ordner server/) auf meinem Linux-Server, der als
VM/LXC auf Proxmox läuft und auf dem Docker bereits installiert ist.

Zugang: SSH, die Daten gebe ich dir separat. Speichere Passwörter, Tokens
oder Schlüssel nirgendwo außer in der .env auf dem Server, und gib sie
nicht in deinen Antworten aus, außer dem neuen API_TOKEN am Ende.

Vorgehen:
1. Per SSH verbinden. Zuerst nur prüfen, nichts ändern:
   - `docker --version` und `docker compose version` (Compose v2 nötig)
   - `docker ps` → welche Container laufen schon?
   - `ss -ltnp` → ist Port 8000 frei? Falls nicht, in Schritt 3 PORT in
     der .env auf einen freien Port setzen.
   - `df -h` → mindestens 10 GB frei?
   - Läuft schon ein Reverse Proxy (Nginx Proxy Manager, Caddy, Traefik)?
   Berichte mir das Ergebnis kurz.

2. Code holen (nach /opt/media-studio, falls nicht anders gewünscht):
     sudo mkdir -p /opt/media-studio && sudo chown "$USER" /opt/media-studio
     git clone https://github.com/VitosStudios/hello-world.git /opt/media-studio
   Falls das Repo privat ist und der Clone scheitert: stopp und frag mich
   nach einem Deploy Key oder Personal Access Token (nur Lesezugriff).
   Den Branch nimmst du aus meiner Anweisung. Wenn ich keinen nenne:
   `claude/video-audio-converter-apk-jkk1E`, solange er nicht in main
   gemergt ist.

3. Konfigurieren:
     cd /opt/media-studio/server
     cp .env.example .env
     sed -i "s/^API_TOKEN=.*/API_TOKEN=$(openssl rand -hex 32)/" .env
   Andere Werte nur ändern, wenn nötig (z. B. PORT bei Konflikt).
   `chmod 600 .env`.

4. Starten:
     docker compose up -d --build
   Warten, bis `docker compose ps` den Container als "healthy" zeigt
   (erster Start dauert ein paar Minuten).

5. Testen:
     curl -fsS http://127.0.0.1:8000/api/health
     TOKEN=$(grep ^API_TOKEN .env | cut -d= -f2)
     curl -fsS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8000/api/formats
   Dann einen echten Link-Job testen:
     curl -fsS -H "Authorization: Bearer $TOKEN" \
       -F url=https://www.youtube.com/watch?v=jNQXAC9IVRw -F format=mp3 \
       http://127.0.0.1:8000/api/jobs
   Mit der zurückgegebenen id per GET /api/jobs/<id> abfragen, bis status
   "done" oder "error" ist. Bei "error": `docker compose logs --tail 100`
   ansehen und die Ursache melden (häufig: YouTube blockt die Server-IP
   oder verlangt Login; dann nicht selbst Cookies einrichten, sondern mich
   fragen).

6. Erreichbarkeit: Öffne KEINE Ports in Firewall oder Router und ändere
   KEINE bestehenden Container oder Proxy-Konfigurationen ohne meine
   Zustimmung. Schlag mir stattdessen vor, wie ich den Dienst per HTTPS
   erreichbar mache (vorhandenen Reverse Proxy nutzen, Tailscale oder
   Cloudflare Tunnel).

7. Am Ende melden:
   - URL im LAN (http://<server-ip>:<port>)
   - den API_TOKEN (für Web-Oberfläche und Android-App)
   - Ergebnis des Testjobs
   - Update-Befehl: cd /opt/media-studio && git pull && cd server && docker compose up -d --build
```
