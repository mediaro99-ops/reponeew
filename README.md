# ProfitRide 1.0.0

**Creat de Plesia Razvan**

Proiect Android nativ (Java) pentru calcularea rapidă a rentabilității ofertelor de cursă afișate pe ecran.

## Ce este inclus

- Setări clare „Când merită cursa”:
  - minim RON/km;
  - minim RON/oră;
  - profit minim RON.
- Consum auto personalizat în L/100 km, editabil manual sau cu butoanele + / −.
- Preț combustibil RON/litru.
- Cost/km manual opțional (util inclusiv pentru EV).
- Calcul automat cost/km și profit estimat.
- Overlay flotant peste alte aplicații.
- Captură ecran prin MediaProjection, cu permisiunea explicită a utilizatorului.
- OCR local cu Google ML Kit Text Recognition.
- Card live: preț, distanță, timp, RON/km, RON/oră, profit și verdict MERITĂ / NU MERITĂ.
- Mod „Simulează ofertă” pentru testare fără Uber/Bolt.
- Credit vizibil: „Creat de Plesia Razvan”.

## Build în Android Studio

1. Dezarhivează proiectul.
2. În Android Studio: **Open** și selectează folderul `ProfitRideAndroid`.
3. Așteaptă **Gradle Sync**. Dacă Android Studio cere Android SDK 35 sau JDK 17, acceptă instalarea/configurarea recomandată.
4. Pentru APK de test: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
5. APK-ul debug va fi creat în `app/build/outputs/apk/debug/app-debug.apk`.

Pentru o versiune release destinată distribuției, folosește **Build > Generate Signed App Bundle or APK** și semnează aplicația cu propriul keystore.

## Prima pornire pe telefon

1. Deschide ProfitRide.
2. Configurează „Când merită cursa” și costurile mașinii.
3. Apasă **START ANALIZĂ**.
4. Permite „Afișare peste alte aplicații”.
5. Acceptă captura ecran Android.
6. Deschide aplicația de șofer. Cardul ProfitRide rămâne flotant și încearcă să citească oferta vizibilă.

## Observație despre detectarea ofertelor

OCR-ul este funcțional și generic. Uber/Bolt își pot schimba interfața, textele și ordinea datelor; de aceea, recunoașterea reală poate necesita ajustări ale parserului pentru anumite versiuni sau tipuri de carduri. Aplicația nu modifică și nu controlează aplicațiile de șofer și nu acceptă/refuză automat curse.

## Confidențialitate

Codul ProfitRide procesează cadrele capturate în memorie pentru OCR și nu include logică pentru salvarea sau încărcarea capturilor către un server.

## Build online cu GitHub Actions

Proiectul include `.github/workflows/build-apk.yml`. După încărcarea fișierelor într-un repository GitHub, workflow-ul **Build ProfitRide APK** pornește automat pe `main`/`master` sau manual din fila **Actions**. APK-ul de test este publicat ca artifact cu numele **ProfitRide-APK** și conține `ProfitRide.apk`.

Instrucțiunile foarte scurte sunt în `BUILD_ONLINE_GITHUB.md`.
