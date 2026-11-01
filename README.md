# Tesla Miles

Records your Tesla's mileage and location automatically through Tesla's official Fleet API. The dashboard
at http://localhost:8080 shows daily, monthly and yearly miles, where the car is now (place name and street
address), and each drive with its start and end places, route and distance. Everything can be exported as CSV.

## How it works

- Every 5 minutes it checks your vehicle list. That call never wakes the car.
- When a car comes online, and on every check while it's driving, it reads the odometer and GPS position.
  While driving, that gives a route point about every 5 minutes.
- While a car is online but parked, it reads the odometer at most once an hour, so the car can still fall asleep.
- The odometer is cumulative. If this PC is off for a while, no miles are lost: they show up on the next
  reading, credited to the day that reading was taken.
- Drives are detected from the readings. A drive the app only saw before and after (too short to catch
  in gear, or made while the app was down) still shows up with the correct miles and start/end places,
  but its time is shown as a range.
- Each place the car parks is turned into a street address, plus a name for businesses and landmarks, using
  OpenStreetMap's free Nominatim service. Each spot is looked up once and cached, at most one request every
  1.5 seconds. Only coordinates are sent. Set `geocoding.enabled=false` to turn this off.
- You can name places yourself (e.g. "Home", "Work") with **Name this place** on the dashboard. Any position
  within 150 m of a named place uses your name.
- Data stays on this machine in `data/` (H2 database, plus the app's key pair). The location history is
  sensitive, so the dashboard only listens on localhost.

## One-time setup

Tesla makes every Fleet API app, including personal ones, register and publish a public key.
The dashboard walks you through these steps and tracks which ones are done.

1. **Host the public key.** The easiest free option is GitHub Pages:
   - Create a public repo named `<your-github-username>.github.io`.
   - Start the app once (`mvn spring-boot:run`) and copy the key from the dashboard. It's also saved at
     `data/com.tesla.3p.public-key.pem`.
   - Commit it to the repo as `.well-known/appspecific/com.tesla.3p.public-key.pem`, and add an empty
     file named `.nojekyll` at the repo root. Without that file, GitHub Pages won't serve the `.well-known` folder.
   - Check that `https://<you>.github.io/.well-known/appspecific/com.tesla.3p.public-key.pem` loads.
2. **Create a Tesla developer app** at https://developer.tesla.com:
   - Allowed origin: `https://<you>.github.io`
   - Allowed redirect URI: `http://localhost:8080/auth/callback`
   - Scopes: Vehicle Information and Vehicle Location
   - Tesla bills Fleet API usage and asks for a payment method. Personal use like this generally stays within the
     monthly credit, but check their current pricing.
3. Copy `config/application.properties.example` to `config/application.properties` and fill in the Client ID
   and Client Secret. Outside North America, also set the EU base URL shown in the file.
4. Restart the app. In the dashboard, enter `<you>.github.io` and click **Register**, then **Connect Tesla**.

## Exports

- **Drives CSV**: one row per drive with start/end time, miles, and start/end place name, address and coordinates
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
| `tesla.parked-poll-interval` | `PT1H` | Minimum gap between odometer reads while parked and awake |
| `server.port` | `8080` | Dashboard port. If you change it, also update the redirect URI here and at Tesla |
