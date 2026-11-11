# UDM Monitor

Android aplikacija (Kotlin + Jetpack Compose) za praćenje UniFi Dream Machinea (UDM, UDM Pro/SE, UDR, UCG) uživo.

## Što prikazuje

- **Pregled** – WAN download/upload uživo + graf zadnjih ~5 minuta (dodirni graf za očitanje), CPU/RAM/temperatura/uptime gatewaya, uređaji s najvećim prometom trenutno.
- **Uređaji** – svi aktivni klijenti: ime, **IP**, **MAC**, trenutni ↓/↑ promet, ukupni promet, SSID/signal, mreža, proizvođač. Pretraga i sortiranje (promet, ukupno, ime, IP). Dodir na uređaj otvara njegove konekcije.
- **Konekcije** – **odlazne**, **dolazne** (npr. port forward) i lokalne konekcije iz conntrack tablice: protokol, stanje, LAN uređaj ↔ udaljena adresa:port, servis (HTTPS, DNS, WireGuard…), brzina i bajtovi po konekciji. Filtri: samo aktivne, TCP/UDP/ICMP, tekst.

Osvježava se svakih 1–10 s (postavka) dok je aplikacija na ekranu.

## Kako radi

| Podatak | Izvor |
|---|---|
| WAN, gateway, klijenti | UniFi OS API: `POST /api/auth/login`, `GET /proxy/network/api/s/<site>/stat/device`, `stat/sta`, `stat/health` |
| Konekcije | SSH na UDM → `cat /proc/net/nf_conntrack` + `ip -o addr show` (za razlikovanje LAN-a i WAN-a) |

UniFi API ne daje popis pojedinačnih konekcija, zato SSH. Aplikacija na UDM-u ništa ne mijenja, osim ako ostaviš uključeno *brojanje bajtova po konekciji* (`nf_conntrack_acct=1`, vrijedi do restarta).

## Postavljanje

1. **Lokalni korisnik**: UniFi OS → Admins & Users → novi admin, *Restrict to local access only*, uloga za Network **View Only**. (Cloud račun s 2FA ne radi.)
2. **SSH** (za konekcije): UniFi OS → Settings → Control Plane → Console → SSH → uključi i postavi lozinku. U aplikaciji: korisnik `root`, ta lozinka.
3. U aplikaciji upiši adresu (npr. `192.168.1.1`), korisnika i lozinku → *Spremi i spoji se*.

Lozinke se spremaju šifrirano (Android Keystore). HTTPS certifikat i SSH ključ UDM-a pamte se pri prvom spajanju (TOFU) – ako se promijene, aplikacija odbija spajanje dok ih ne resetiraš u Postavkama.

## Build

- **Android Studio**: otvori mapu, pričekaj Gradle sync, Run.
- **Komandna linija**: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub**: pushaj u repo; workflow `.github/workflows/build.yml` izgradi APK i stavi ga pod *Actions → Artifacts*.

Zahtjevi: JDK 17, Android SDK 35. minSdk 26 (Android 8).

## Napomene

- Ako ti se smjer prometa kod uređaja čini obrnutim (ovisi o verziji Network aplikacije), uključi *Zamijeni download/upload* u Postavkama.
- Brzine u UniFi API-ju (`*-r` polja) kontroler osvježava svakih nekoliko sekundi, pa kraći interval od 2 s ne donosi puno.
- Aplikacija radi samo dok je otvorena (nema pozadinskog servisa).
