SkyOptima (Prototype)

Minimal Java 17 + HTML/CSS/JS prototype.

- Java backend: `com.sun.net.httpserver.HttpServer` on port 8080
- Serves static files from `public/`
- Endpoints:
  - `GET /startSimulation`
  - `GET /stopSimulation`
  - `GET /updateSimulation` → `{ world, aircraft[], warnings[], events[] }`
  - `GET /getAlerts` → `{ alerts: [{id,a1,a2,advisory}] }`
  - `GET /configure?n=..&tick=..`
  - `GET /configureAdvanced?n=..&tick=..&world=..&sep=..`
  - `GET /reset`
  - `GET /status`

Build and Run (Windows PowerShell)

```powershell
javac -d out src\skyoptima\*.java
java -cp out skyoptima.Main
```

Then open `http://localhost:8080` in your browser.

Notes
- 2D simulation on a wrap-around grid (default 100×100) with 3–10 aircraft.
- Predicts near-future conflicts and automatically applies avoidance (opposite heading changes ≈±25°, temporary ±15% speed).
- Emits events and advisory alerts; UI shows aircraft and statuses:
  - white: normal
  - yellow (blinking): conflict course
  - green: deviated (brief hold)

