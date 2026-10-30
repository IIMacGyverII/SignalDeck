# SignalDeck regions: values and sources

Every value in `app/src/main/assets/regions/regions.json`, with the source it came from (all checked 2026-10-29).
Fields listed under **Unverified** were set from a regional plan, a secondary source or an inference, not from a national primary source; treat them as defaults to review.
Frequencies are in MHz. A dash means not set (no established value). `RegionsDataTest` checks the file.

## How to update

The table ships with app releases only (owner decision 2026-10-29: no remote update). To change a value: edit `regions.json`, add or replace its
`sources` entry (field, url, title, checked date), add or remove the field in `unverified`, update the matching row here, run
`./gradlew testReleaseUnitTest --tests '*RegionsDataTest' --rerun` (`--rerun`: Gradle does not track assets as a test input, so a data-only
change would otherwise reuse the last result), and note it in the changelog. An unknown country uses its IARU region's default entry.

## Unreachable sources (2026-10-29)

- aprs.org (Cloudflare challenge; not bypassed), wiki.brandmeister.network (bot check; the BrandMeister API was used instead)
- ofcom.org.uk (Ofcom IR 2030), irts.ie, ntc.gov.ph, ris.bka.gv.at (bot checks); mimit.gov.it (refused); pzk.org.pl (maintenance)
- karl.or.kr band plan (login), acma.gov.au CB page (timeout; the legislation register was used), fcc.gov GMRS page (403), ISED RBR-4 page (404; mirror used)
- informacoes.anatel.gov.br (maintenance), traficom.fi 4AG/2026M and 15AW/2026M texts (404), NZART 2026 plan (image-only PDF)
- Not yet read: IARU Region 1 VHF Handbook v10.03 (February 2026, iaru-r1.org), which may supersede the v10.02 used here

## IARU Region 1 (Europe, Africa, Middle East) (iaru1)

IARU Region 1. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| Licence-free | - | - |
| Station ID interval | - | - |
| BrandMeister country TG | - | - |

**Unverified:** `dapnetHz` (a national usage note, not a Region 1 value; countries without their own value keep the mark)

Notes: Regional band-plan values. 439.9875 MHz POCSAG is a national-usage note in the plan; several countries cannot transmit there.

## IARU Region 2 (the Americas) (iaru2)

IARU Region 2. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.39 | [IARU Region 2 Band Plan (September 2020)](https://www.iaru.org/wp-content/uploads/2020/10/IARU-Region-2-Band-plan.pdf) |
| FM calling 2 m / 70 cm | 146.52 / - | [IARU Region 2 Band Plan (September 2020)](https://www.iaru.org/wp-content/uploads/2020/10/IARU-Region-2-Band-plan.pdf) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 420-450 | [IARU Region 2 Band Plan (September 2020)](https://www.iaru.org/wp-content/uploads/2020/10/IARU-Region-2-Band-plan.pdf) |
| Licence-free | - | - |
| Station ID interval | - | - |
| BrandMeister country TG | - | - |

**Unverified:** none

Notes: The Region 2 plan names no 70 cm FM calling frequency; not every country has 420-450 MHz (many have 430-440).

## IARU Region 3 (Asia-Pacific) (iaru3)

IARU Region 3. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf) |
| APRS (alternative) | 144.39 | [IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf) |
| FM calling 2 m / 70 cm | - / - | - |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 430-440 | [IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf) |
| Licence-free | - | - |
| Station ID interval | - | - |
| BrandMeister country TG | - | - |

**Unverified:** none

Notes: R3-004 suggests 144.800 MHz as the APRS spot frequency; as of 2018 MARTS, RAST, SARTS and ORARI used 144.390 and CRAC, CTARL and HARTS 144.640. No regional FM calling frequencies.

## United States (US)

IARU Region 2, falls back to `iaru2`. Units: mi.

| Field | Value | Source |
|---|---|---|
| APRS | 144.39 | [ARRL: Automatic Packet Reporting System](http://www.arrl.org/automatic-packet-reporting-system-qst)<br>[IARU Region 2 Band Plan (September 2020)](https://www.iaru.org/wp-content/uploads/2020/10/IARU-Region-2-Band-plan.pdf) |
| FM calling 2 m / 70 cm | 146.52 / 446 | [ARRL Band Plan](https://www.arrl.org/band-plan) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | yes | [NOAA Weather Radio All Hazards](https://www.weather.gov/nwr/)<br>[NWR Specific Area Message Encoding (SAME)](https://www.weather.gov/nwr/nwrsame) |
| Amateur bands | 144-148, 420-450 | [47 CFR 97.301 Authorized frequency bands](https://www.ecfr.gov/current/title-47/part-97/section-97.301) |
| Licence-free | FRS: 462.55-467.7125, 22 ch, 2.0 W<br>GMRS: 462.55-467.725, 30 ch, 50.0 W<br>MURS: 151.82-154.6, 5 ch, 2.0 W | [47 CFR 95.563 / 95.567 FRS channels and power](https://www.ecfr.gov/current/title-47/section-95.563)<br>[47 CFR 95.1705 / 95.1763 / 95.1767 GMRS licence, channels, power](https://www.ecfr.gov/current/title-47/section-95.1705)<br>[47 CFR 95.2763 / 95.2767 MURS channels and power](https://www.ecfr.gov/current/title-47/section-95.2763) |
| Station ID interval | 10 min | [47 CFR 97.119 Station identification](https://www.ecfr.gov/current/title-47/section-97.119) |
| BrandMeister country TG | 3100 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

Notes: NOAA Weather Radio: seven channels 162.400-162.550 MHz with SAME. BrandMeister TG 3100 is the US nationwide bridge (310 is a TAC group).

## Canada (CA)

IARU Region 2, falls back to `iaru2`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.39 | [RAC Canadian Amateur Two Metre (2m) Band Plan (October 2020)](https://www.rac.ca/mivahih/2021/01/TwoMetre_Bandplan_ND2020TCA.pdf) |
| FM calling 2 m / 70 cm | 146.52 / 446 | [RAC Canadian Amateur Two Metre (2m) Band Plan (October 2020)](https://www.rac.ca/mivahih/2021/01/TwoMetre_Bandplan_ND2020TCA.pdf)<br>[RAC 432 MHz (70 cm) band plan page](https://wp.rac.ca/432-mhz-70-cm-page/) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | [Environment Canada: Weatheradio (no longer in service)](https://www.canada.ca/en/environment-climate-change/services/weatheradio.html) |
| Amateur bands | 144-148, 430-450 | [ISED RBR-4 Issue 3 (July 2022), Standards for the Operation of Radio Stations in the Amateur Radio Service (mirror; ISED URL returned 404)](https://www.ece.unb.ca/petersen/amateur-radio-files/RBR04e-Operating-Standards-2022-Jul.pdf)<br>[RAC 432 MHz (70 cm) band plan page](https://wp.rac.ca/432-mhz-70-cm-page/) |
| Licence-free | FRS/GMRS: 462.55-467.7125, 22 ch, 2.0 W | [ISED RSS-210 Issue 10, Annex E (FRS/GMRS)](https://ised-isde.canada.ca/site/spectrum-management-telecommunications/sites/default/files/attachments/2022/RSS-210-Issue10A1.pdf) |
| Station ID interval | 30 min | [ISED RBR-4 Issue 3 (July 2022), Standards for the Operation of Radio Stations in the Amateur Radio Service (mirror; ISED URL returned 404)](https://www.ece.unb.ca/petersen/amateur-radio-files/RBR04e-Operating-Standards-2022-Jul.pdf) |
| BrandMeister country TG | 302 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

Notes: Weatheradio Canada (162 MHz, SAME) was shut down in 2026; US NWR stations may still be heard near the border.

## Mexico (MX)

IARU Region 2, falls back to `iaru2`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.39 | [IARU Region 2 Band Plan (September 2020)](https://www.iaru.org/wp-content/uploads/2020/10/IARU-Region-2-Band-plan.pdf) |
| FM calling 2 m / 70 cm | 146.52 / - | [IARU Region 2 Band Plan (September 2020)](https://www.iaru.org/wp-content/uploads/2020/10/IARU-Region-2-Band-plan.pdf) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | yes | [IFT Cuadro Nacional de Atribucion de Frecuencias (notes MX107, MX114A, MX135, MX140)](https://www.ift.org.mx/sites/default/files/c-erro-06-cnaf_06-22_version_accesible.pdf) |
| Amateur bands | 144-148, 430-440 | [IFT Cuadro Nacional de Atribucion de Frecuencias (notes MX107, MX114A, MX135, MX140)](https://www.ift.org.mx/sites/default/files/c-erro-06-cnaf_06-22_version_accesible.pdf) |
| Licence-free | FRS (banda libre): 462.5625-467.7125, 14 ch, 0.5 W | [IFT Cuadro Nacional de Atribucion de Frecuencias (notes MX107, MX114A, MX135, MX140)](https://www.ift.org.mx/sites/default/files/c-erro-06-cnaf_06-22_version_accesible.pdf)<br>[Mexico's Family Radio Service (FRS) equivalent (secondary, 2006)](https://www.qsl.net/wd9ewk/xe-frs.html) |
| Station ID interval | - | - |
| BrandMeister country TG | 334 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `callingHz`, `weatherRadio`, `licenseFree`

Notes: APRS and 2 m calling come from the Region 2 plan (no FMRE/IFT document found). 440-450 MHz is not amateur in Mexico, so there is no 446.000. The 162.400-162.550 MHz channels are protected for early-alert broadcasting (CNAF MX114A; SASMEX seismic alerts reportedly use SAME), not NOAA-style weather.

## Brazil (BR)

IARU Region 2, falls back to `iaru2`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 145.57 | [Plano de Bandas de 2m 2024 conforme Ato 926/2024 (private, secondary)](https://gcremeri.freeshell.org/wiki/pub/files/2024%20Plano%20Bandas%20BR%202m%202024-02-05v1.1.pdf) |
| FM calling 2 m / 70 cm | 146.52 / - | [Anatel Cartilha do Servico Radioamador (2026-06), section 9.2](https://sistemas.anatel.gov.br/anexar-api/publico/anexos/download/e2bee3ec4783c5c41a8ce4affe7baef8) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 430-440 | [Plano de Bandas de 2m 2024 conforme Ato 926/2024 (private, secondary)](https://gcremeri.freeshell.org/wiki/pub/files/2024%20Plano%20Bandas%20BR%202m%202024-02-05v1.1.pdf)<br>[IARU Region 2 Band Plan (September 2020)](https://www.iaru.org/wp-content/uploads/2020/10/IARU-Region-2-Band-plan.pdf) |
| Licence-free | - | - |
| Station ID interval | - | - |
| BrandMeister country TG | 724 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `amateurBands`

Notes: 145.570 MHz is widely reported (aprs.fi, secondary plans) but not confirmed from LABRE or Anatel. 144-148 / 430-440 MHz from the revoked Res. 452/2006 and secondary plans; the current Ato 926/2024 page was down. FRS-type 'radiacao restrita' radios (462/467 MHz, 0.5 W, Res. 680/2017) are sold, but the regulation text was not read, so none is listed. Anatel sets no fixed ID interval (Ato 3448/2026).

## Argentina (AR)

IARU Region 2, falls back to `iaru2`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.93 | [ENACOM Res. 3635-E/2017, Anexo A band plan](https://www.argentina.gob.ar/normativa/286986_res3635-Anexo-5_jpg/archivo) |
| APRS (alternative) | 430.93 | [ENACOM Res. 3635-E/2017, Anexo A band plan](https://www.argentina.gob.ar/normativa/286986_res3635-Anexo-5_jpg/archivo) |
| FM calling 2 m / 70 cm | 146.52 / - | [ENACOM Res. 3635-E/2017, Anexo A band plan](https://www.argentina.gob.ar/normativa/286986_res3635-Anexo-5_jpg/archivo) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 430-440 | [ENACOM Res. 3635-E/2017, Anexo A band plan](https://www.argentina.gob.ar/normativa/286986_res3635-Anexo-5_jpg/archivo) |
| Licence-free | Uso familiar: 462.55625-462.71875, 7 ch, 0.5 W | [SC Res. 2750/1998 (uso familiar portable radios)](https://www.argentina.gob.ar/normativa/nacional/norma-55385/texto) |
| Station ID interval | 10 min | [ENACOM Res. 3635-E/2017 Reglamento General de Radioaficionados (rule 1.5.8)](https://www.argentina.gob.ar/normativa/nacional/norma-286986/texto) |
| BrandMeister country TG | 722 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

## United Kingdom (GB)

IARU Region 1, falls back to `iaru1`. Units: mi.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [RSGB Band Plan (effective 1 January 2026)](https://rsgb.services/public/bandplans/docs/260125_rsgb_band_plan_2026.pdf) |
| APRS (alternative) | 433.8 | [RSGB Band Plan (effective 1 January 2026)](https://rsgb.services/public/bandplans/docs/260125_rsgb_band_plan_2026.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [RSGB Band Plan (effective 1 January 2026)](https://rsgb.services/public/bandplans/docs/260125_rsgb_band_plan_2026.pdf) |
| FM SSTV | 433.4 | [RSGB Band Plan (effective 1 January 2026)](https://rsgb.services/public/bandplans/docs/260125_rsgb_band_plan_2026.pdf) |
| DAPNET | 439.9875 | [RSGB Band Plan (effective 1 January 2026)](https://rsgb.services/public/bandplans/docs/260125_rsgb_band_plan_2026.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [RSGB Band Plan (effective 1 January 2026)](https://rsgb.services/public/bandplans/docs/260125_rsgb_band_plan_2026.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448) |
| Station ID interval | - | [Ofcom Amateur Radio Licence (21 Feb 2024), clause 21 (mirror; ofcom.org.uk unreachable)](https://wiki.oarc.uk/_media/uk-licence-2024-02-21.pdf) |
| BrandMeister country TG | 2350 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `licenseFree`

Notes: 433.800 is for APRS where 144.800 cannot be used. 146-147 MHz is a UK extension for Full licensees with an NoV only (not listed as a band here). Ofcom sets no fixed ID interval (identify 'as frequently as practicable'). Ofcom IR 2030 was unreachable, so PMR446/LPD433 are not confirmed nationally. BrandMeister TG 235 is a call channel; 2350 is 'United Kingdom'.

## Ireland (IE)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [ComReg 09/45R5 Amateur Station Licence Guidelines (May 2023), Annex 1](https://www.comreg.ie/media/2023/05/ComReg-0945R5.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | - | - |
| BrandMeister country TG | 272 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `callingHz`, `sstvHz`, `licenseFree`

Notes: IRTS was unreachable; APRS, calling and SSTV follow the Region 1 plan. A vendor list gives DAPNET 433.625 MHz (doubtful, not set). ERC/REC 70-03 notes Ireland allows voice on 433 MHz SRD with limits.

## Germany (DE)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [DARC Bandplan 2 m (August 2017)](https://www.darc.de/fileadmin/filemounts/distrikte/h/ortsverbaende/02/Nuetzliches/Bandplan_2_m_August_2017.pdf) |
| APRS (alternative) | 432.5 | [DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [DARC Bandplan 2 m (August 2017)](https://www.darc.de/fileadmin/filemounts/distrikte/h/ortsverbaende/02/Nuetzliches/Bandplan_2_m_August_2017.pdf)<br>[DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| FM SSTV | 433.4 | [DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| DAPNET | 439.9875 | [DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [AFuV Anlage 1 (amateur frequency bands)](https://www.gesetze-im-internet.de/afuv_2005/anlage_1.html) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W<br>Freenet: 149.021875-149.115625, 6 ch, 1.0 W | [BNetzA Vfg. 45/2025 Freenet (copy)](https://www.eurofunker.lima-city.de/PDF/Vfg_45_2025.pdf)<br>[ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | 10 min | [AFuV section 11 (call sign every ten minutes)](https://www.gesetze-im-internet.de/afuv_2005/__11.html) |
| BrandMeister country TG | 262 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

## France (FR)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [Plan de la bande IARU Region 1 144-146 MHz, French usage (F6ETI, 2017)](https://ref73.webador.fr/_downloads/0f24e91d6fef71a878553fd9f5dbde08) |
| APRS (alternative) | 432.5 | [Plan de la bande IARU Region 1 430-440 MHz, French usage (F6ETI, 2017)](https://ref73.webador.fr/_downloads/729843b37bd9d1dd0dc0a7a419b94319) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [Plan de la bande IARU Region 1 144-146 MHz, French usage (F6ETI, 2017)](https://ref73.webador.fr/_downloads/0f24e91d6fef71a878553fd9f5dbde08)<br>[Plan de la bande IARU Region 1 430-440 MHz, French usage (F6ETI, 2017)](https://ref73.webador.fr/_downloads/729843b37bd9d1dd0dc0a7a419b94319) |
| FM SSTV | 433.4 | [Plan de la bande IARU Region 1 430-440 MHz, French usage (F6ETI, 2017)](https://ref73.webador.fr/_downloads/729843b37bd9d1dd0dc0a7a419b94319) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [ARCEP Decision 2013-1515 annex (amateur bands)](https://www.r-e-f.org/images/stories/ARCEP/2013_1515.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | 15 min | [ARCEP Decision 2012-1241 article 4 (identification every 15 minutes)](https://www.arcep.fr/uploads/tx_gsavis/12-1241.pdf) |
| BrandMeister country TG | 208 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `dapnetHz`, `licenseFree`

Notes: The French plan (2017, REF73) puts digital links in 439.800-439.975; DAPNET 439.9875 is from a vendor list only.

## Netherlands (NL)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [VERON VHF-UHF Operating frequencies (2025-11-17)](https://vhf-uhf.veron.nl/frequenties/operating/) |
| APRS (alternative) | 432.5 | [VERON VHF-UHF Operating frequencies (2025-11-17)](https://vhf-uhf.veron.nl/frequenties/operating/) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [VERON Frequentiegebruik (from the Nationaal Frequentieplan)](https://veron.nl/vereniging/radioamateur/frequentiegebruik/) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | 5 min | [Regeling gebruik van frequentieruimte met meldingsplicht 2015, art. 10 (call sign every five minutes)](https://wetten.overheid.nl/BWBR0036375/) |
| BrandMeister country TG | 204 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `callingHz`, `sstvHz`, `dapnetHz`, `licenseFree`

Notes: VERON publishes no national 2 m/70 cm plan and follows the Region 1 plan. 436-440 MHz is secondary.

## Belgium (BE)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [UBA V/U/SHF Bandplan (links the IARU Region 1 plans)](https://www.uba.be/nl/v-u-shf/bandplan)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [IARU Region 1 VHF Handbook v10.02, 430 MHz plan (iaru-r1.org), checked 2026-10-30](https://www.iaru-r1.org/wp-content/uploads/2024/11/VHF_Handbook_V10_02.pdf)<br>[UBA V/U/SHF Bandplan (links the IARU Region 1 plans)](https://www.uba.be/nl/v-u-shf/bandplan)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [IARU Region 1 VHF Handbook v10.02, 430 MHz plan (iaru-r1.org), checked 2026-10-30](https://www.iaru-r1.org/wp-content/uploads/2024/11/VHF_Handbook_V10_02.pdf) (433.400 SSTV (FM/AFSK); 144.500 is the SSB image-mode centre)<br>[UBA V/U/SHF Bandplan (links the IARU Region 1 plans)](https://www.uba.be/nl/v-u-shf/bandplan)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02, 430 MHz plan (iaru-r1.org), checked 2026-10-30](https://www.iaru-r1.org/wp-content/uploads/2024/11/VHF_Handbook_V10_02.pdf) (national usage note q)<br>[ON4WX source review, ON0LG paging installation (issue #95, 2026-10-30)](https://github.com/IIMacGyverII/SignalDeck/issues/95)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [BIPT/IBPT national frequency plan](https://www.bipt.be/secteurs/telecoms/spectre/plan-des-frequences)<br>[BIPT Frequency bands and technical characteristics authorised for radio amateurs (2019)](https://www.bipt.be/file/cc73d96153bbd5448a56f19d925d05b1379c7f21/dd6ba0d4094625ded08277255de9ac1143f4976c/freq-en.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [BIPT/IBPT licence-free private use (PMR446)](https://www.bipt.be/utilisateurs/frequences-radio/utilisation-privee-de-loisir/utilisation-libre-de-radio)<br>[BIPT/IBPT interface specifications CB, PMR446, amateur](https://www.bipt.be/secteurs/telecoms/equipements-hertziens/specifications-d-interfaces/cb-pmr446-amateur)<br>[ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | - | - |
| BrandMeister country TG | 206 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `licenseFree.LPD433` (PMR446 confirmed)

Notes: UBA's band plan is the IARU Region 1 plan. Reviewed by ON4WX 2026-10-30 (issue #95): 2 m and 70 cm allocations, APRS 144.800, FM calling 145.500, DAPNET 439.9875 and PMR446 confirmed. 433.500 (70 cm FM calling) and 433.400 (FM SSTV) are in the Region 1 430 MHz plan itself; 144.500 is the 2 m image-mode centre in the all-mode (SSB) segment, so this FM radio's SSTV default is 433.400. LPD433: the national plan lists short-range devices at 433.05-434.79 MHz, but the channel plan and voice use are not confirmed for Belgium. No ID interval found in the BIPT 2019 decision; none is inferred (the app's 10-minute reminder is only a reminder).

## Italy (IT)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-434, 435-438 | [Italian amateur allocations table (secondary, undated)](https://www.qsl.net/iw2hdc/pag12.html) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | 10 min | [Allegato 26 Codice delle comunicazioni elettroniche (DM 1 March 2021)](https://www.ari-rivarolo.org/docs/Allegato-26-CdC.pdf) |
| BrandMeister country TG | 222 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `callingHz`, `sstvHz`, `amateurBands`, `licenseFree`

Notes: ARI's national plan and MIMIT were unreachable; values follow the Region 1 plan. 434-435 and 438-440 MHz are not amateur in Italy, so 439.9875 cannot be used; a vendor list gives DAPNET 144.5125 / 144.9375 MHz (not set).

## Spain (ES)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [URE-hosted Plan de bandas de la IARU 2m y 70cm (in force since January 2006)](https://ea4ffi.ure.es/radioaficionados%20page%20docs/PLAN%20DE%20BANDAS%20DE%20LA%20IARU%20-%202m%20y%2070cm.pdf) |
| APRS (alternative) | 433.8 | [URE-hosted Plan de bandas de la IARU 2m y 70cm (in force since January 2006)](https://ea4ffi.ure.es/radioaficionados%20page%20docs/PLAN%20DE%20BANDAS%20DE%20LA%20IARU%20-%202m%20y%2070cm.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [URE-hosted Plan de bandas de la IARU 2m y 70cm (in force since January 2006)](https://ea4ffi.ure.es/radioaficionados%20page%20docs/PLAN%20DE%20BANDAS%20DE%20LA%20IARU%20-%202m%20y%2070cm.pdf) |
| FM SSTV | 433.4 | [URE-hosted Plan de bandas de la IARU 2m y 70cm (in force since January 2006)](https://ea4ffi.ure.es/radioaficionados%20page%20docs/PLAN%20DE%20BANDAS%20DE%20LA%20IARU%20-%202m%20y%2070cm.pdf) |
| DAPNET | 439.9875 | [URE-hosted Plan de bandas de la IARU 2m y 70cm (in force since January 2006)](https://ea4ffi.ure.es/radioaficionados%20page%20docs/PLAN%20DE%20BANDAS%20DE%20LA%20IARU%20-%202m%20y%2070cm.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [Orden IET/1311/2013 (consolidated), annex and art. 29.1](https://www.boe.es/buscar/pdf/2013/BOE-A-2013-7624-consolidado.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | 10 min | [Orden IET/1311/2013 (consolidated), annex and art. 29.1](https://www.boe.es/buscar/pdf/2013/BOE-A-2013-7624-consolidado.pdf) |
| BrandMeister country TG | 214 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `dapnetHz`, `licenseFree`

Notes: The URE-hosted plan dates from 2006. It names 439.9875 as POCSAG centre 'per the national plan'; a vendor list says Spain uses 144.8625.

## Portugal (PT)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [REP: CQ0PSL novo sistema de APRS (2020)](https://rep.pt/wp/2020/10/04/cq0psl-novo-sistema-de-aprs/) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-435, 438-440 | [QNAF Anexo 6, amateur services (2013 addendum copy)](https://groups.google.com/group/ct-comunicacoes-e-tecnologias/attach/e8e4eefce728/anexo6_Adenda_2013_QNAF.pdf?part=0.1) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | - | - |
| BrandMeister country TG | 268 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `callingHz`, `sstvHz`, `dapnetHz`, `amateurBands`, `licenseFree`

Notes: Band table from a 2013 QNAF addendum (435-438 MHz amateur-satellite only); recheck against current ANACOM. ERC/REC 70-03 notes Portugal excludes analogue audio other than voice on 433 MHz SRD.

## Switzerland (CH)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [USKA Bandplaene (uses the DARC 2 m 2017 and 70 cm 2025 plans)](https://uska.ch/de/funkamateure/repeater-liste-und-bandplaene/bandplaene/bandplan/)<br>[DARC Bandplan 2 m (August 2017)](https://www.darc.de/fileadmin/filemounts/distrikte/h/ortsverbaende/02/Nuetzliches/Bandplan_2_m_August_2017.pdf) |
| APRS (alternative) | 432.5 | [USKA Bandplaene (uses the DARC 2 m 2017 and 70 cm 2025 plans)](https://uska.ch/de/funkamateure/repeater-liste-und-bandplaene/bandplaene/bandplan/)<br>[DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [USKA Bandplaene (uses the DARC 2 m 2017 and 70 cm 2025 plans)](https://uska.ch/de/funkamateure/repeater-liste-und-bandplaene/bandplaene/bandplan/)<br>[DARC Bandplan 2 m (August 2017)](https://www.darc.de/fileadmin/filemounts/distrikte/h/ortsverbaende/02/Nuetzliches/Bandplan_2_m_August_2017.pdf)<br>[DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| FM SSTV | 433.4 | [USKA Bandplaene (uses the DARC 2 m 2017 and 70 cm 2025 plans)](https://uska.ch/de/funkamateure/repeater-liste-und-bandplaene/bandplaene/bandplan/)<br>[DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| DAPNET | 439.9875 | [USKA Bandplaene (uses the DARC 2 m 2017 and 70 cm 2025 plans)](https://uska.ch/de/funkamateure/repeater-liste-und-bandplaene/bandplaene/bandplan/)<br>[DARC Bandplan 70 cm (May 2025)](https://www.darc.de/fileadmin/filemounts/referate/vus/bandplaene/UHF_Bandplan_70_cm_Mai_2025.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [BAKOM Hilfstabellen OUUS Art. 6 (2025-10-28)](https://www.bakom.admin.ch/dam/it/sd-web/NMOiPhk4y4w5/20251028_Hilfstabellen%20it.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | 10 min | [VVNF SR 784.102.11 Art. 2 (copy)](https://www.lexaris.de/book/version/documentflat/head/227562) |
| BrandMeister country TG | 228 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `licenseFree`, `stationIdMinutes`

Notes: USKA uses the DARC plans. The 10-minute ID rule is from VVNF Art. 2; that it applies to amateurs is an inference.

## Austria (AT)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [OeVSV UKW-Bandplan (August 2023)](https://adl612.oevsv.at/export/shared/.content/.galleries/Downloads_Referate/UKW-Referat-Downloads/UKW-Bandplan.pdf) |
| APRS (alternative) | 432.5 | [OeVSV UKW-Bandplan (August 2023)](https://adl612.oevsv.at/export/shared/.content/.galleries/Downloads_Referate/UKW-Referat-Downloads/UKW-Bandplan.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [OeVSV UKW-Bandplan (August 2023)](https://adl612.oevsv.at/export/shared/.content/.galleries/Downloads_Referate/UKW-Referat-Downloads/UKW-Bandplan.pdf) |
| FM SSTV | 433.4 | [OeVSV UKW-Bandplan (August 2023)](https://adl612.oevsv.at/export/shared/.content/.galleries/Downloads_Referate/UKW-Referat-Downloads/UKW-Bandplan.pdf) |
| DAPNET | 439.93 | [OeVSV UKW-Bandplan (August 2023)](https://adl612.oevsv.at/export/shared/.content/.galleries/Downloads_Referate/UKW-Referat-Downloads/UKW-Bandplan.pdf) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-439.1, 439.92-439.94 | [BGBl. II Nr. 61/2023 Anlage 4 Amateurfunkfrequenzbereiche](https://cert05external-www.360.lexisnexis.at/media/attachment/nor40251381ii_61_2023_anlage_4/pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448)<br>[ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845) |
| Station ID interval | - | - |
| BrandMeister country TG | 232 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `dapnetHz`, `licenseFree`

Notes: 439.1-440 MHz is receive-only except 439.930 MHz (20 kHz, pager relays), so 439.9875 cannot be transmitted; that DAPNET uses 439.930 is not confirmed. ID interval not found (RIS unreachable).

## Sweden (SE)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [SSA Bandplan 144-146 MHz (2020) and 432-438 MHz](https://www.ssa.se/ssa/dokument/bandplaner-vhf-shf/) |
| APRS (alternative) | 432.5 | [SSA Bandplan 144-146 MHz (2020) and 432-438 MHz](https://www.ssa.se/ssa/dokument/bandplaner-vhf-shf/) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [SSA Bandplan 144-146 MHz (2020) and 432-438 MHz](https://www.ssa.se/ssa/dokument/bandplaner-vhf-shf/) |
| FM SSTV | 433.4 | [SSA Bandplan 144-146 MHz (2020) and 432-438 MHz](https://www.ssa.se/ssa/dokument/bandplaner-vhf-shf/) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 432-438 | [PTSFS 2025:1 (licence-exempt transmitters incl. amateur, KDR, PMR446)](https://pts.se/contentassets/b05c8a7d01a64783aafc6a19e1b78590/ptsfs-2025-1-foreskrifter-om-undantag-fran-tillstandsplikt-for-anvandning-av-vissa-radiosandare.pdf)<br>[SSA Bandplan 144-146 MHz (2020) and 432-438 MHz](https://www.ssa.se/ssa/dokument/bandplaner-vhf-shf/) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.015 W<br>KDR 444: 444.5875-444.9875, 8 ch, 2.0 W<br>Jaktradio 155 MHz: 155.4-155.525, 6 ch, 5.0 W<br>SRBR 31 MHz: 30.925-31.375, - ch, 5.0 W | [PTSFS 2025:1 (licence-exempt transmitters incl. amateur, KDR, PMR446)](https://pts.se/contentassets/b05c8a7d01a64783aafc6a19e1b78590/ptsfs-2025-1-foreskrifter-om-undantag-fran-tillstandsplikt-for-anvandning-av-vissa-radiosandare.pdf) |
| Station ID interval | - | - |
| BrandMeister country TG | 240 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `licenseFree`

Notes: Nordic 70 cm is 432-438 MHz, so 439.9875 POCSAG is outside the band. PTS requires the call sign 'at short intervals' with no minute figure.

## Norway (NO)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [NRRL Baandplan 2 m (January 2019)](https://nrrl.no/wp-content/uploads/2m.pdf) |
| APRS (alternative) | 432.5 | [NRRL Baandplan 70 cm](https://nrrl.no/wp-content/uploads/70cm.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [NRRL Baandplan 2 m (January 2019)](https://nrrl.no/wp-content/uploads/2m.pdf)<br>[NRRL Baandplan 70 cm](https://nrrl.no/wp-content/uploads/70cm.pdf) |
| FM SSTV | 433.4 | [NRRL Baandplan 70 cm](https://nrrl.no/wp-content/uploads/70cm.pdf) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 432-438 | [Forskrift om radioamatoervirksomhet FOR-2026-03-23-465](https://lovdata.no/dokument/SF/forskrift/2026-03-23-465) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>KDR 444: 444.6-444.975, 6 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [Fribruksforskriften FOR-2012-01-19-77 section 40 (KDR 444, PMR446)](https://lovdata.no/dokument/SF/forskrift/2012-01-19-77) |
| Station ID interval | - | - |
| BrandMeister country TG | 242 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

Notes: 144.825 is a secondary APRS frequency. 70 cm is 432-438 MHz. The regulation requires the call sign 'at short intervals' with no minute figure.

## Denmark (DK)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [EDR Baandplan VUSHF](https://www.edr.dk/baandplan-vushf/) |
| APRS (alternative) | 432.5 | [EDR Baandplan VUSHF](https://www.edr.dk/baandplan-vushf/) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [EDR Baandplan VUSHF](https://www.edr.dk/baandplan-vushf/) |
| FM SSTV | 433.4 | [EDR Baandplan VUSHF](https://www.edr.dk/baandplan-vushf/) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 432-438 | [BEK nr 939 af 18/06/2025, Bilag 4](https://www.lovtidende.dk/api/pdf/250849) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W | [BEK nr 939 af 18/06/2025, Bilag 4](https://www.lovtidende.dk/api/pdf/250849) |
| Station ID interval | 10 min | [BEK nr 939 af 18/06/2025, Bilag 4](https://www.lovtidende.dk/api/pdf/250849) |
| BrandMeister country TG | 238 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

Notes: EDR prints 'SSTV U272' as 432.4000 (a typo: U272 is 433.400). No KDR 444 in Denmark. 70 cm is 432-438 MHz.

## Finland (FI)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [SRAL: IARU taajuusjakosuositus (defers to the IARU Region 1 plan)](https://www.sral.fi/toiminta/maaraykset/iaru-taajuusjakosuositus/)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [SRAL: IARU taajuusjakosuositus (defers to the IARU Region 1 plan)](https://www.sral.fi/toiminta/maaraykset/iaru-taajuusjakosuositus/)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [SRAL: IARU taajuusjakosuositus (defers to the IARU Region 1 plan)](https://www.sral.fi/toiminta/maaraykset/iaru-taajuusjakosuositus/)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 432-438, 438.0625-438.1625 | [Traficom Radioamatooriviestinnan taajuusalueet (15.8.2025)](https://www.traficom.fi/files/media/file/Radioamat%C3%B6%C3%B6riviestinn%C3%A4n%20taajuusalueet.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [Traficom Maarays 15 AV/2025 M (licence-exempt radios: PMR446, RHA68, SRD)](https://www.traficom.fi/sites/default/files/media/regulation/M%C3%A4%C3%A4r%C3%A4ys%2015AV2025M.pdf) |
| Station ID interval | 10 min | [Traficom Regulation 6K/2019 M explanatory notes (call sign every 10 minutes)](https://finlex.fi/en/authorities/regulations/traficom-viestinta/2019/45134) |
| BrandMeister country TG | 244 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `callingHz`, `sstvHz`, `licenseFree`, `stationIdMinutes`

Notes: SRAL publishes no national VHF plan and defers to the Region 1 plan. 155.400 MHz +n x 25 kHz is for dog trackers only. No KDR 444. The hobby/hunting voice service RHA68 is at 68 MHz, outside the module's range (group E 5 W licence-exempt).

## Poland (PL)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf)<br>[DAPNET transmitter list (shows activity per country; has no frequency field)](https://hampager.de/api/transmitters) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [Rozporzadzenie Ministra Cyfryzacji, Dz.U. 2026 poz. 406](https://eli.gov.pl/api/acts/DU/2026/406/text/I/D20260406.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845)<br>[ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448) |
| Station ID interval | - | - |
| BrandMeister country TG | 260 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `callingHz`, `sstvHz`, `dapnetHz`, `licenseFree`

Notes: PZK's site was down; values follow the Region 1 plan. DAPNET is active in Poland (many transmitters), frequency not confirmed.

## Czech Republic (CZ)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [Vyhlaska 156/2005 Sb. (amateur bands; IARU R1 segments mandatory)](https://www.zakonyprolidi.cz/cs/2005-156)<br>[Cesky radioklub VKV (points to the IARU R1 VHF Handbook)](https://ceskyradioklub.cz/provoz/vkv)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [Vyhlaska 156/2005 Sb. (amateur bands; IARU R1 segments mandatory)](https://www.zakonyprolidi.cz/cs/2005-156)<br>[Cesky radioklub VKV (points to the IARU R1 VHF Handbook)](https://ceskyradioklub.cz/provoz/vkv)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [Vyhlaska 156/2005 Sb. (amateur bands; IARU R1 segments mandatory)](https://www.zakonyprolidi.cz/cs/2005-156)<br>[Cesky radioklub VKV (points to the IARU R1 VHF Handbook)](https://ceskyradioklub.cz/provoz/vkv)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf)<br>[DAPNET transmitter list (shows activity per country; has no frequency field)](https://hampager.de/api/transmitters) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [Vyhlaska 156/2005 Sb. (amateur bands; IARU R1 segments mandatory)](https://www.zakonyprolidi.cz/cs/2005-156) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>LPD433: 433.05-434.79, 69 ch, 0.01 W | [ERC Recommendation 70-03 (edition October 2026), Annex 1 and Annex A](https://docdb.cept.org/document/845)<br>[ECC/DEC/(15)05 on PMR446 (amended March 2018)](https://docdb.cept.org/document/448) |
| Station ID interval | - | - |
| BrandMeister country TG | 230 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `dapnetHz`, `licenseFree`

Notes: Decree 156/2005 makes the IARU Region 1 segments binding. DAPNET is active, frequency not confirmed.

## South Africa (ZA)

IARU Region 1, falls back to `iaru1`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.8 | [Configuring APRS reporting in South Africa (secondary)](https://gadgeteer.co.za/configuring-aprs-reporting-south-africa/)<br>[SARL band plans (hosts the IARU R1 VHF/UHF plans)](https://mysarl.org.za/bandplans/) |
| FM calling 2 m / 70 cm | 145.5 / 433.5 | [SARL band plans (hosts the IARU R1 VHF/UHF plans)](https://mysarl.org.za/bandplans/)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| FM SSTV | 433.4 | [SARL band plans (hosts the IARU R1 VHF/UHF plans)](https://mysarl.org.za/bandplans/)<br>[IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf) |
| DAPNET | 439.9875 | [IARU Region 1 VHF Handbook v10.02 (March 2024), 144 and 430 MHz band plans (mirror of the iaru-r1.org edition)](https://erau.ee/images/ULL/VHF_Handbook_V10_02.pdf)<br>[DAPNET transmitter list (shows activity per country; has no frequency field)](https://hampager.de/api/transmitters) |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [ICASA National Radio Frequency Plan 2026 (hosted by SARL)](https://mysarl.org.za/wp-content/uploads/2026/07/Published-National-Radio-Frequency-Plan-2026-1.pdf) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W<br>SRD 433: 433.05-434.79, - ch, 0.01 W | [ICASA Notice 1822 of 2023, RFSR Annexure B (licence-exempt apparatus)](https://www.gov.za/sites/default/files/gcis_document/202305/48643gon1822.pdf) |
| Station ID interval | - | - |
| BrandMeister country TG | 655 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `callingHz`, `sstvHz`, `dapnetHz`

Notes: SARL hosts the IARU Region 1 plans as its VHF plan. ICASA's exemption lists PMR446 at 446-446.2 MHz, 500 mW; the 2026 frequency plan lists 446-446.1 MHz only.

## Australia (AU)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 145.175 | [WIA Australian Amateur Band Plans, 2 m (29/07/2015)](https://www.wia.org.au/members/bandplans/data/documents/Background%20-%202%20metre%20band%20plan%202015.pdf)<br>[WIA Australian Band Plan Review 2025 (consultation TAC-2025/01)](https://www.wia.org.au/members/tac/consultation/documents/WIA%20Australian%20Band%20Plan%20Review%20v6.0%20-%20RELEASE%20VERSION.pdf) |
| APRS (alternative) | 439.1 | [WIA Australian Amateur Band Plans, 70 cm (2015)](https://www.wia.org.au/members/bandplans/data/documents/Background%20-%2070%20cm%20band%20plan%202015.pdf)<br>[WIA Australian Band Plan Review 2025 (consultation TAC-2025/01)](https://www.wia.org.au/members/tac/consultation/documents/WIA%20Australian%20Band%20Plan%20Review%20v6.0%20-%20RELEASE%20VERSION.pdf) |
| FM calling 2 m / 70 cm | 146.5 / 439 | [WIA Australian Amateur Band Plans, 2 m (29/07/2015)](https://www.wia.org.au/members/bandplans/data/documents/Background%20-%202%20metre%20band%20plan%202015.pdf)<br>[WIA Australian Amateur Band Plans, 70 cm (2015)](https://www.wia.org.au/members/bandplans/data/documents/Background%20-%2070%20cm%20band%20plan%202015.pdf)<br>[WIA Australian Band Plan Review 2025 (consultation TAC-2025/01)](https://www.wia.org.au/members/tac/consultation/documents/WIA%20Australian%20Band%20Plan%20Review%20v6.0%20-%20RELEASE%20VERSION.pdf) |
| FM SSTV | 145.1 | [WIA Australian Amateur Band Plans, 2 m (29/07/2015)](https://www.wia.org.au/members/bandplans/data/documents/Background%20-%202%20metre%20band%20plan%202015.pdf) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 430-450 | [Radiocommunications (Amateur Stations) Class Licence 2023](https://legislation.gov.au/F2023L01648/asmade/2023-12-12/text/original/pdf) |
| Licence-free | UHF CB: 476.425-477.4125, 80 ch, 5.0 W | [Radiocommunications (Citizen Band Radio Stations) Class Licence 2025](https://www.legislation.gov.au/F2025L01088/asmade/2025-09-15/text/original/epub/OEBPS/document_1/document_1.html) |
| Station ID interval | 10 min | [Radiocommunications (Amateur Stations) Class Licence 2023](https://legislation.gov.au/F2023L01648/asmade/2023-12-12/text/original/pdf) |
| BrandMeister country TG | 505 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

Notes: 145.100 is the 2015 plan's non-voice (RTTY/SSTV/fax) frequency; the 2025 review no longer names it. 420-430 MHz has been closed to amateurs since 2013. 439.9875 is in a repeater-output block, so no DAPNET default.

## New Zealand (NZ)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.575 | [NZART New Zealand 2 m and 70 cm Band Plan 2022](https://nzart.org.nz/wp-content/uploads/2024/11/NZ-2m-70cm-Band-Plan-2022-final.pdf) |
| APRS (alternative) | 432.575 | [NZART New Zealand 2 m and 70 cm Band Plan 2022](https://nzart.org.nz/wp-content/uploads/2024/11/NZ-2m-70cm-Band-Plan-2022-final.pdf) |
| FM calling 2 m / 70 cm | 144.5 / 432.5 | [NZART New Zealand 2 m and 70 cm Band Plan 2022](https://nzart.org.nz/wp-content/uploads/2024/11/NZ-2m-70cm-Band-Plan-2022-final.pdf) |
| FM SSTV | 145.25 | [NZART New Zealand 2 m and 70 cm Band Plan 2022](https://nzart.org.nz/wp-content/uploads/2024/11/NZ-2m-70cm-Band-Plan-2022-final.pdf) |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 430-440 | [RSM GURL for Amateur Radio Operators Notice 2025](https://www.rsm.govt.nz/assets/Uploads/documents/gazette/radiocommunications-regulations-general-user-radio-licence-for-amateur-radio-operators-notice-2025.pdf) |
| Licence-free | PRS (UHF CB): 476.425-477.4125, 80 ch, 8.3 W | [RSM GURL for Citizen Band Radio Notice 2011 (PRS 476-477 MHz)](https://www.rsm.govt.nz/assets/Uploads/pdfs/gazette/43ec454362/radiocommunications-regulations-general-licence-for-cb-radio-notice-2011.pdf) |
| Station ID interval | 15 min | [RSM GURL for Amateur Radio Operators Notice 2025](https://www.rsm.govt.nz/assets/Uploads/documents/gazette/radiocommunications-regulations-general-user-radio-licence-for-amateur-radio-operators-notice-2025.pdf) |
| BrandMeister country TG | 530 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

Notes: NZART's June 2026 plan is image-only and was not read; values are from the 2022 plan. Repeater channel pairs assumed as in Australia.

## Japan (JP)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.66 | [APRS operating frequencies in Japan (secondary wiki)](https://wikiwiki.jp/dcn/APRS/Frequency) |
| APRS (alternative) | 144.64 | [APRS operating frequencies in Japan (secondary wiki)](https://wikiwiki.jp/dcn/APRS/Frequency) |
| FM calling 2 m / 70 cm | 145 / 433 | [JARL band plan (effective 2025-07-17)](https://www.jarl.org/Japanese/A_Shiryo/A-3_Band_Plan/bandplan20250717.pdf) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [JARL band plan (effective 2025-07-17)](https://www.jarl.org/Japanese/A_Shiryo/A-3_Band_Plan/bandplan20250717.pdf) |
| Licence-free | Tokutei shoudenryoku (simplex): 422.05-422.3, 21 ch, 0.01 W<br>Tokutei shoudenryoku (duplex 421 MHz): 421.575-421.9125, 28 ch, 0.01 W<br>Tokutei shoudenryoku (duplex 440 MHz): 440.025-440.3625, 28 ch, 0.01 W<br>Digital community radio 142 MHz: 142.934375-142.984375, - ch, 0.5 W<br>Digital community radio 146 MHz: 146.934375-146.984375, - ch, 0.5 W | [Specified low-power radio telephone stations (secondary)](https://pedia.3rd-in.co.jp/wiki/%E7%84%A1%E7%B7%9A%E9%9B%BB%E8%A9%B1%E7%94%A8%E7%89%B9%E5%AE%9A%E5%B0%8F%E9%9B%BB%E5%8A%9B%E7%84%A1%E7%B7%9A%E5%B1%80) |
| Station ID interval | 10 min | [Musenkyoku Unyo Kisoku art. 30 (amateur call sign about every 10 minutes)](https://laws.e-gov.go.jp/api/1/lawdata/325M50080000017) |
| BrandMeister country TG | 440 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `aprsHz`, `aprsAltHz`, `licenseFree`

Notes: 144.660 MHz is 1200 baud (the usual APRS), 144.640 MHz is 9600 baud; JARL names no APRS frequency (144.60-144.70 is wideband data). 145.00/433.00 are calling and emergency frequencies. 351 MHz digital simple radio is outside the module's range and is registered, not licence-free.

## South Korea (KR)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | - | - |
| FM calling 2 m / 70 cm | - / - | - |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 430-440 | [IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf) |
| Licence-free | Low-power voice 447.6 MHz: 447.6-447.85, 21 ch, 0.005 W<br>Low-power voice 447.9 MHz: 447.8625-447.9875, 11 ch, 0.01 W | [MSIT Notice 2026-9, radio equipment for stations opened without notification](https://www.law.go.kr/LSW/flDownload.do?flSeq=161548805) |
| Station ID interval | - | - |
| BrandMeister country TG | 450 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `amateurBands`

Notes: No Korean source for APRS (144.620 is reported but unconfirmed) or calling frequencies; KARL's band plan needs a login. The app uses the Region 3 default. Analogue 448 MHz family radio (0.5 W) reportedly ends 2026-12-31; its frequencies are unconfirmed.

## China (CN)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.64 | [IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf) |
| FM calling 2 m / 70 cm | - / - | - |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 430-440 | [IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf) |
| Licence-free | Public walkie-talkie: 409.75-409.9875, 20 ch, 0.5 W | [National Radio Office notice 2025 No. 1 (public walkie-talkie, 409 MHz)](https://www.beijing.gov.cn/zhengce/zhengcefagui/qtwj/202505/W020250508533710250422.pdf) |
| Station ID interval | 10 min | [MIIT Order 67 amateur radio station rules, art. 37 (reprint)](https://www.waizi.org.cn/doc/227732.html) |
| BrandMeister country TG | 460 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `amateurBands`, `stationIdMinutes`

Notes: 145.000 and 433.000 MHz appear as emergency (not calling) frequencies in unofficial copies of the band plan.

## Thailand (TH)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | 144.39 | [RAST 2 metre band plan for Thailand (effective 2014-07-24)](https://www.qsl.net/rast/text/2mbandplan.htm)<br>[IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf) |
| FM calling 2 m / 70 cm | 145 / - | [RAST 2 metre band plan for Thailand (effective 2014-07-24)](https://www.qsl.net/rast/text/2mbandplan.htm) |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146.5 | [RAST 2 metre band plan for Thailand (effective 2014-07-24)](https://www.qsl.net/rast/text/2mbandplan.htm)<br>[RAST: Thai amateur licensing 2016 (70 cm receive only)](https://www.qsl.net/rast/text/licensing2016.html) |
| Licence-free | - | - |
| Station ID interval | - | - |
| BrandMeister country TG | 520 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** none

Notes: Not 145.525: RAST and the Region 3 plan give 144.390. 144.900 is the general calling channel, 145.000 calling and emergency. Amateurs may only receive on 70 cm (RAST 2016). Thai CB is at 245 MHz, outside the module's range.

## India (IN)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | - | - |
| FM calling 2 m / 70 cm | - / - | - |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-146, 434-438 | [Indian Wireless Telegraphs (Amateur Service) Rules 1978 (text copy)](https://www.qsl.net/vu2kyp/rule.html)<br>[Wikipedia: amateur radio frequency bands in India (secondary)](https://en.wikipedia.org/wiki/List_of_amateur_radio_frequency_bands_in_India) |
| Licence-free | PMR446: 446-446.2, 16 ch, 0.5 W | [G.S.R. 1047(E) 2018 licence-exemption rules, Table V (copy)](https://www.legitquest.com/act/use-of-low-power-and-very-low-power-short-range-radio-frequency-devices-exemption-from-licensing-requirement-rules-2018/9F4C) |
| Station ID interval | 10 min | [Indian Wireless Telegraphs (Amateur Service) Rules 1978 (text copy)](https://www.qsl.net/vu2kyp/rule.html) |
| BrandMeister country TG | 404 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `amateurBands`, `licenseFree`, `stationIdMinutes`

Notes: No national APRS source: 144.390 is seen only in station comments, so the Region 3 default is used. 70 cm (434-438 MHz, secondary) is from a secondary source.

## Philippines (PH)

IARU Region 3, falls back to `iaru3`. Units: km.

| Field | Value | Source |
|---|---|---|
| APRS | - | - |
| FM calling 2 m / 70 cm | - / - | - |
| FM SSTV | - | - |
| DAPNET | - | - |
| Weather radio (162 MHz, SAME) | no | - |
| Amateur bands | 144-148, 430-450 | [IARU Region 3 Band Plan R3-004 Rev. 2 (November 2024)](https://iaru-r3.org/wp-content/uploads/2025/01/R3-004-Band-Plans-IARU-Region-3.pdf)<br>[NTC MC-02-06-2019 amateur rules (unreachable: Cloudflare)](https://ntc.gov.ph/wp-content/uploads/2019/07/MC-02-06-2019.pdf) |
| Licence-free | - | - |
| Station ID interval | - | - |
| BrandMeister country TG | 515 | [BrandMeister API v2 talkgroup list (TG number = MCC; names as published)](https://api.brandmeister.network/v2/talkgroup) |

**Unverified:** `amateurBands`

Notes: No national APRS source (the Philippines is not in the Region 3 APRS footnote; 144.390 is reported but unconfirmed), so the Region 3 default is used. 440-450 MHz is amateur in the Philippines and Australia (RR 5.270); class privileges (NTC) were unreachable.
