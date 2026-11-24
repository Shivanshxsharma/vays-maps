# Delhi road tiles

Drivable roads of Delhi cut into ~1.1 km tiles (`tiles/<row>_<col>.json.gz`, `index.json`), downloaded by the
Vays Maps app along a route. Built with `tools tiles` from the Geofabrik northern-India OpenStreetMap extract.

Map data © OpenStreetMap contributors. This tile set is a derived database and is made available under the
[Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).

## Places (app/src/main/assets/places.db)

About 216,000 named places (shops, restaurants, clinics, offices, colonies, roads) built with `tools places` from:

* **Overture Maps Foundation**, places theme (Meta, Microsoft, Foursquare, AllThePlaces and others):
  [CDLA-Permissive-2.0](https://cdla.dev/permissive-2-0/); the Foursquare part is Apache-2.0, AllThePlaces is CC0.
  See [overturemaps.org/attribution](https://docs.overturemaps.org/attribution/).
* **OpenStreetMap** contributors, ODbL. The merged file is made available under the ODbL 1.0.
* `community.tsv`: places sent in by riders.

Cleaned on the way: wrong pins (address or postcode elsewhere), pages that aren't places, duplicates, unsupported second pins
of famous places. Expect some mistakes anyway: small shops move and close.
