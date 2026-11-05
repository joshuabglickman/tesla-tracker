# Tesla Miles

Records your Tesla's mileage and location automatically through Tesla's official Fleet API. The dashboard
at http://localhost:8080 shows daily, monthly and yearly miles, where the car is now (place name and street
address), and each drive with its start and end places, route and distance. Everything can be exported as CSV.

## How it works

- Every 5 minutes it checks your vehicle list. That call never wakes the car.
- While a car is awake, every check reads its odometer, gear and GPS position, so a drive is noticed within
  5 minutes and gets a route point about every 5 minutes.
- Being read keeps a car awake, so once it has sat parked for 15 minutes the app pauses reads for 21 minutes
  to let it sleep. If it's still awake after the pause (Sentry Mode, climate keeping, charging), reads resume
  and the cycle repeats. A drive that starts during a pause is noticed when the pause ends.
- The odometer is cumulative. If this PC is off for a while, no miles are lost: they show up on the next
  reading, credited to the day that reading was taken.
- Drives are detected from the readings. A drive the app only saw before and after (too short to catch
  in gear, or made while the app was down) still shows up with the correct miles and start/end places,
  but its time is shown as a range.
- Home and Work are learned automatically. Tesla doesn't share the car's saved Home and Work addresses, but
  the first time you navigate to either one, the app saves it under that name. Home is also guessed from
  where the car is parked at 3 a.m. on most nights (once it has seen at least 3 nights). A place you named
  yourself always wins. If a learned place is wrong, delete it and name the right spot yourself.
- If the car's navigation has a route set while driving, the drive also records where it was navigating to
  (the destination's name and position), even if you stopped somewhere else.
- Each place the car parks is turned into a street address, plus a name for businesses and landmarks, using
  OpenStreetMap's free Nominatim service. Each spot is looked up once and cached, at most one request every
  1.5 seconds. Only coordinates are sent. Set `geocoding.enabled=false` to turn this off.
- You can name places yourself (e.g. "Home", "Work") with **Name this place** on the dashboard, or add, edit and
  delete them in **Named places** (a place you add is found on the map from its address). Any position
  within a named place's radius (150 m unless you change it) uses your name. You can also give it an address, for places where the map's
  address is wrong. When you navigate to one of your places by its name, you are at it anywhere within 500 m,
  e.g. in a big parking lot.
- A drive that starts where the previous one ended starts from the place that one arrived at, so the trip back
  from a navigation destination keeps its name.
- A stop shorter than the 5-minute poll is never seen in Park, so a drive also ends where it comes back into
  one of your named places (e.g. going home for forgotten keys), and the next drive starts there.
- Data stays on this machine in `data/` (H2 database, plus the app's key pair). The location history is
  sensitive, so the dashboard only listens on localhost.

## One-time setup

Tesla makes every Fleet API app, including personal ones, register and publish a public key.
The dashboard walks you through these steps and tracks which ones are done.

1. **Host the public key.** This repo publishes it with GitHub Pages from the `docs/` folder at
   https://tesla.schoolforjews.com/.well-known/appspecific/com.tesla.3p.public-key.pem
   - The key in `docs/` must match `data/com.tesla.3p.public-key.pem`, which the app generates on first start.
     If `data/` is ever lost, copy the new key into `docs/` and push.
   - GitHub: Settings → Pages → Deploy from a branch → `main` / `docs`, custom domain `tesla.schoolforjews.com`,
     Enforce HTTPS.
   - DNS (Domain.com): `CNAME` record `tesla` → `joshuabglickman.github.io`.
2. **Create a Tesla developer app** at https://developer.tesla.com:
   - Allowed origin: `https://tesla.schoolforjews.com`
   - Allowed redirect URI: `http://localhost:8081/auth/callback` (this PC uses port 8081; see `config/application.properties`)
   - Scopes: Vehicle Information and Vehicle Location
   - Tesla bills Fleet API usage and asks for a payment method. Personal use like this generally stays within the
     monthly credit, but check their current pricing.
3. Copy `config/application.properties.example` to `config/application.properties` and fill in the Client ID
   and Client Secret. Outside North America, also set the EU base URL shown in the file.
4. Restart the app. In the dashboard, enter `tesla.schoolforjews.com` and click **Register**, then **Connect Tesla**.

## Exports

- **Drives CSV**: one row per drive starting with its local date and time (the middle of the range for drives
  whose exact time isn't known), miles, and where it went from and to as "name, address"; then exact start/end time, miles, and start/end place name, address and coordinates, and the navigation destination if one was set
  (useful as a mileage log)
- **Daily CSV**: miles per day
- **Readings CSV**: every raw reading (odometer, gear, latitude, longitude, speed)

## Run it automatically

```powershell
.\install-autostart.ps1          # build, start now, and start at every Windows sign-in
.\install-autostart.ps1 -Uninstall
```

To run it by hand instead: `mvn spring-boot:run`, or `java -jar target/tesla-tracking.jar`.

## Settings

All of these go in `config/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `tesla.poll-interval` | `PT5M` | How often to check the vehicle list |
| `tesla.idle-before-sleep-pause` | `PT15M` | How long the car sits parked and awake before reads pause |
| `tesla.sleep-pause` | `PT21M` | How long reads pause to let the car fall asleep |
| `server.port` | `8080` | Dashboard port. If you change it, also update the redirect URI here and at Tesla |
