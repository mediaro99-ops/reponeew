# Build online ProfitRide — varianta simplă

Nu ai nevoie de Android Studio.

## Pași

1. Intră pe GitHub și creează un repository nou, de exemplu `ProfitRide`.
2. Dezarhivează `ProfitRide_GITHUB_READY.zip` pe laptop.
3. În repository: **Add file → Upload files** și încarcă **toate fișierele și folderele din arhivă**, inclusiv folderul ascuns `.github`.
4. Apasă **Commit changes**.
5. GitHub pornește automat build-ul. Intră la **Actions → Build ProfitRide APK**.
6. Deschide ultimul build și așteaptă bifa verde.
7. Jos, la **Artifacts**, descarcă **ProfitRide-APK**.
8. Dezarhivează artifact-ul; înăuntru găsești `ProfitRide.apk`.
9. Trimite `ProfitRide.apk` pe telefon și instalează-l.

Dacă build-ul nu pornește automat, intră la **Actions → Build ProfitRide APK → Run workflow → Run workflow**.

## Important

- APK-ul rezultat este varianta **debug**, suficientă pentru testare direct pe telefon.
- Nu ai nevoie de cheie de semnare pentru această versiune de test.
- Pentru publicare în Google Play se va face ulterior un build `release` semnat.
- Prima compilare poate dura câteva minute deoarece GitHub descarcă Android SDK, Gradle și dependențele ML Kit.
