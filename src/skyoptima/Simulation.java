package skyoptima;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class Simulation {
    static class Aircraft {
        String id;
        double x;
        double y;
        double speed; // units per second
        double directionDeg; // 0..360, 0 to the right, 90 up
        String status; // normal, conflict_course, deviated, warning_proximity, critical_proximity
        int deviatedTicksRemaining;
        double baseSpeed;
    }

    static class PairKey {
        final String a;
        final String b;
        PairKey(String a, String b) {
            if (a.compareTo(b) <= 0) { this.a = a; this.b = b; }
            else { this.a = b; this.b = a; }
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PairKey)) return false;
            PairKey k = (PairKey) o;
            return Objects.equals(a, k.a) && Objects.equals(b, k.b);
        }
        @Override public int hashCode() { return Objects.hash(a, b); }
    }

    static class Event {
        final String id;
        final String type;
        final String message;
        final long ts;
        Event(String type, String message) {
            this.id = UUID.randomUUID().toString();
            this.type = type;
            this.message = message;
            this.ts = System.currentTimeMillis();
        }
    }

    static class Alert {
        final String id;
        final String a1;
        final String a2;
        final String advisory;
        final long ts;
        Alert(String a1, String a2, String advisory) {
            this.id = UUID.randomUUID().toString();
            this.a1 = a1; this.a2 = a2; this.advisory = advisory; this.ts = System.currentTimeMillis();
        }
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> task;
    private final Random random = new Random();

    private int worldSize = 100;
    private int minSeparation = 8; // units
    private int tickMs = 1000;
    private int aircraftCount = 5;

    private final Map<String, Aircraft> idToAircraft = new LinkedHashMap<>();
    private final Deque<Event> events = new ArrayDeque<>();
    private final List<Alert> activeAlerts = new ArrayList<>();
    private final Set<PairKey> pairsUnderAvoidance = new HashSet<>();

    Simulation() {
        reseedAircraft();
    }

    void start() {
        if (task != null && !task.isCancelled()) return;
        task = scheduler.scheduleAtFixedRate(this::tick, 0, tickMs, TimeUnit.MILLISECONDS);
        addEvent("info", "Simulation started");
    }

    void stop() {
        if (task != null) task.cancel(false);
        task = null;
        addEvent("info", "Simulation stopped");
    }

    void reset() {
        stop();
        reseedAircraft();
        pairsUnderAvoidance.clear();
        activeAlerts.clear();
        addEvent("reset", "Simulation reset");
    }

    void setAircraftCount(int n) {
        if (n < 3) n = 3; if (n > 10) n = 10;
        this.aircraftCount = n;
        reseedAircraft();
    }

    void setTickMs(int ms) {
        if (ms < 100) ms = 100;
        this.tickMs = ms;
        if (task != null) {
            stop();
            start();
        }
    }

    void setWorldSize(int size) {
        if (size < 50) size = 50; if (size > 1000) size = 1000;
        this.worldSize = size;
    }

    void setMinSeparation(int sep) {
        if (sep < 2) sep = 2; if (sep > worldSize/2) sep = worldSize/2;
        this.minSeparation = sep;
    }

    String getStatusJson() {
        return "{" +
                "\"running\":" + (task != null) + "," +
                "\"tick\":" + tickMs + "," +
                "\"world\":" + worldSize + "," +
                "\"sep\":" + minSeparation + "," +
                "\"n\":" + idToAircraft.size() +
                "}";
    }

    String getUpdateJson() {
        // Also compute predictive warnings here
        List<String> warnings = new ArrayList<>();
        predictConflicts(warnings, false);
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"world\":").append(worldSize).append(",");
        sb.append("\"aircraft\":[");
        boolean first = true;
        for (Aircraft a : idToAircraft.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{")
                    .append("\"id\":\"").append(a.id).append("\",")
                    .append("\"x\":").append((int)Math.round(a.x)).append(',')
                    .append("\"y\":").append((int)Math.round(a.y)).append(',')
                    .append("\"speed\":").append(Math.round(a.speed)).append(',')
                    .append("\"direction\":").append(Math.round(a.directionDeg)).append(',')
                    .append("\"status\":\"").append(escape(a.status)).append("\"")
                    .append("}");
        }
        sb.append("],");
        sb.append("\"warnings\":[");
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\"").append(escape(warnings.get(i))).append("\"");
        }
        sb.append("],");
        sb.append("\"events\":[");
        List<Event> snapshot = new ArrayList<>(events);
        for (int i = 0; i < snapshot.size(); i++) {
            Event e = snapshot.get(i);
            if (i > 0) sb.append(',');
            sb.append("{")
                    .append("\"id\":\"").append(escape(e.id)).append("\",")
                    .append("\"type\":\"").append(escape(e.type)).append("\",")
                    .append("\"message\":\"").append(escape(e.message)).append("\",")
                    .append("\"ts\":").append(e.ts)
                    .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    String getAlertsJson() {
        predictConflicts(null, true);
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"alerts\":[");
        for (int i = 0; i < activeAlerts.size(); i++) {
            Alert a = activeAlerts.get(i);
            if (i > 0) sb.append(',');
            sb.append("{")
                    .append("\"id\":\"").append(escape(a.id)).append("\",")
                    .append("\"a1\":\"").append(escape(a.a1)).append("\",")
                    .append("\"a2\":\"").append(escape(a.a2)).append("\",")
                    .append("\"advisory\":\"").append(escape(a.advisory)).append("\"")
                    .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void reseedAircraft() {
        idToAircraft.clear();
        for (int i = 0; i < aircraftCount; i++) {
            Aircraft a = new Aircraft();
            a.id = "AC" + (i + 1);
            a.x = random.nextDouble() * worldSize;
            a.y = random.nextDouble() * worldSize;
            a.speed = 1 + random.nextDouble() * 2; // 1..3
            a.baseSpeed = a.speed;
            a.directionDeg = random.nextDouble() * 360.0;
            a.status = "normal";
            a.deviatedTicksRemaining = 0;
            idToAircraft.put(a.id, a);
        }
    }

    private void tick() {
        try {
            // Move aircraft
            double dt = tickMs / 1000.0;
            for (Aircraft a : idToAircraft.values()) {
                if (a.deviatedTicksRemaining > 0) a.deviatedTicksRemaining--;
                if (a.deviatedTicksRemaining == 0 && a.status.equals("deviated")) {
                    a.status = "normal";
                }
                
                // Ensure aircraft always maintain minimum speed (can't stop)
                a.speed = Math.max(0.5, a.speed);
                
                double rad = Math.toRadians(a.directionDeg);
                a.x = wrap(a.x + Math.cos(rad) * a.speed * dt);
                a.y = wrap(a.y - Math.sin(rad) * a.speed * dt);
            }

            // Update aircraft status based on proximity (overrides other statuses)
            updateProximityStatus();

            // Detect near-miss (actual separation breach)
            activeAlerts.clear();
            List<Aircraft> list = new ArrayList<>(idToAircraft.values());
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Aircraft p = list.get(i);
                    Aircraft q = list.get(j);
                    double d = wrapDistance(p.x, p.y, q.x, q.y);
                    if (d < minSeparation) {
                        String msg = "Near-miss: " + p.id + " and " + q.id + " d=" + String.format(Locale.US, "%.1f", d);
                        addEvent("near_miss", msg);
                        String advisory = advisoryFor(p, q);
                        activeAlerts.add(new Alert(p.id, q.id, advisory));
                    }
                }
            }

            // Predict and avoid
            List<String> warnings = new ArrayList<>();
            List<PairKey> conflicts = predictConflicts(warnings, false);
            for (PairKey pair : conflicts) {
                if (!pairsUnderAvoidance.contains(pair)) {
                    Aircraft p = idToAircraft.get(pair.a);
                    Aircraft q = idToAircraft.get(pair.b);
                    if (p == null || q == null) continue;
                     // Mark conflict first (this will show yellow blinking for one tick)
                     p.status = "conflict_course";
                     q.status = "conflict_course";
                     addEvent("conflict_predicted", "In collision course: " + p.id + " and " + q.id);

                     // Apply realistic avoidance: coordinated turns away from each other
                     // This will change status to "deviated" (green)
                     applyRealisticAvoidance(p, q);

                    pairsUnderAvoidance.add(pair);
                    addEvent("avoidance_applied", "Successfully deviated: " + p.id + " and " + q.id);
                }
            }

            // Cleanup avoidance pairs when no longer conflicting
            pairsUnderAvoidance.removeIf(pk -> {
                Aircraft p = idToAircraft.get(pk.a);
                Aircraft q = idToAircraft.get(pk.b);
                if (p == null || q == null) return true;
                return !"deviated".equals(p.status) && !"deviated".equals(q.status);
            });
        } catch (Exception e) {
            addEvent("error", "Tick error: " + e.getMessage());
        }
    }

    private List<PairKey> predictConflicts(List<String> warningsOut, boolean forAlerts) {
        List<PairKey> conflicts = new ArrayList<>();
        List<Aircraft> list = new ArrayList<>(idToAircraft.values());
        int horizon = Math.max(5, 5 * 1000 / Math.max(100, tickMs)); // ~5 seconds horizon in steps
        double dt = tickMs / 1000.0;
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Aircraft p = list.get(i);
                Aircraft q = list.get(j);
                double px = p.x, py = p.y, qx = q.x, qy = q.y;
                double prad = Math.toRadians(p.directionDeg);
                double qrad = Math.toRadians(q.directionDeg);
                boolean predicted = false;
                for (int step = 0; step < horizon; step++) {
                    px = wrap(px + Math.cos(prad) * p.speed * dt);
                    py = wrap(py - Math.sin(prad) * p.speed * dt);
                    qx = wrap(qx + Math.cos(qrad) * q.speed * dt);
                    qy = wrap(qy - Math.sin(qrad) * q.speed * dt);
                    double d = wrapDistance(px, py, qx, qy);
                    if (d < minSeparation * 1.3) { // predictive buffer
                        predicted = true; break;
                    }
                }
                if (predicted) {
                    conflicts.add(new PairKey(p.id, q.id));
                    if (warningsOut != null) warningsOut.add("Conflict predicted: " + p.id + " and " + q.id);
                    if (forAlerts) {
                        String advisory = advisoryFor(p, q);
                        activeAlerts.add(new Alert(p.id, q.id, advisory));
                    }
                }
            }
        }
        return conflicts;
    }

    private String advisoryFor(Aircraft p, Aircraft q) {
        double d = wrapDistance(p.x, p.y, q.x, q.y);
        String severity = d < minSeparation ? "IMMEDIATE" : "CAUTION";
        return severity + ": " + p.id + " and " + q.id + " separation " + String.format("%.1f", d) + 
               " (min " + minSeparation + "). " + p.id + " status: " + p.status + 
               ", " + q.id + " status: " + q.status;
    }

    private double wrap(double v) {
        double w = worldSize;
        v %= w; if (v < 0) v += w;
        return v;
    }

    private double wrapDistance(double x1, double y1, double x2, double y2) {
        double dx = Math.abs(x1 - x2);
        double dy = Math.abs(y1 - y2);
        if (dx > worldSize / 2.0) dx = worldSize - dx;
        if (dy > worldSize / 2.0) dy = worldSize - dy;
        return Math.hypot(dx, dy);
    }

    private double normalizeDeg(double d) {
        d %= 360.0; if (d < 0) d += 360.0; return d;
    }

    private void updateProximityStatus() {
        List<Aircraft> list = new ArrayList<>(idToAircraft.values());
        
        // Only reset aircraft that are not in avoidance states
        for (Aircraft a : list) {
            if (!a.status.equals("deviated") && !a.status.equals("conflict_course")) {
                a.status = "normal";
            }
        }
        
        // Check proximity between all pairs
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Aircraft p = list.get(i);
                Aircraft q = list.get(j);
                double d = wrapDistance(p.x, p.y, q.x, q.y);
                
                // Only update status if not already in avoidance states
                if (d < 1.0) {
                    // Within 1 unit - critical proximity (red)
                    if (!p.status.equals("deviated") && !p.status.equals("conflict_course")) {
                        p.status = "critical_proximity";
                    }
                    if (!q.status.equals("deviated") && !q.status.equals("conflict_course")) {
                        q.status = "critical_proximity";
                    }
                } else if (d < minSeparation) {
                    // Within minimum separation - warning proximity (yellow)
                    if (!p.status.equals("critical_proximity") && !p.status.equals("deviated") && !p.status.equals("conflict_course")) {
                        p.status = "warning_proximity";
                    }
                    if (!q.status.equals("critical_proximity") && !q.status.equals("deviated") && !q.status.equals("conflict_course")) {
                        q.status = "warning_proximity";
                    }
                }
            }
        }
    }

    private void applyRealisticAvoidance(Aircraft p, Aircraft q) {
        // Calculate relative position and bearing
        double dx = q.x - p.x;
        double dy = q.y - p.y;
        
        // Handle wrap-around for relative position
        if (dx > worldSize / 2.0) dx -= worldSize;
        if (dx < -worldSize / 2.0) dx += worldSize;
        if (dy > worldSize / 2.0) dy -= worldSize;
        if (dy < -worldSize / 2.0) dy += worldSize;
        
        // Calculate bearing from p to q
        double bearingToQ = Math.toDegrees(Math.atan2(-dy, dx));
        if (bearingToQ < 0) bearingToQ += 360;
        
        // Calculate bearing from q to p
        double bearingToP = Math.toDegrees(Math.atan2(-(-dy), -dx));
        if (bearingToP < 0) bearingToP += 360;
        
        // Both aircraft turn away from each other
        // Aircraft p turns away from q (perpendicular to collision course)
        double pTurn = 45 + random.nextDouble() * 20 - 10; // 35-55 degrees
        if (random.nextBoolean()) pTurn = -pTurn; // random left/right
        
        // Aircraft q turns away from p (opposite direction)
        double qTurn = 45 + random.nextDouble() * 20 - 10; // 35-55 degrees
        if (random.nextBoolean()) qTurn = -qTurn; // random left/right
        
        // Apply turns
        p.directionDeg = normalizeDeg(p.directionDeg + pTurn);
        q.directionDeg = normalizeDeg(q.directionDeg + qTurn);
        
        // Set status and timing
        p.status = "deviated";
        q.status = "deviated";
        p.deviatedTicksRemaining = Math.max(8, 2000 / tickMs); // hold for ~2 seconds
        q.deviatedTicksRemaining = Math.max(8, 2000 / tickMs);
        
        // Ensure minimum speed (aircraft can't stop)
        p.speed = Math.max(0.5, p.speed); // minimum 0.5 units/second
        q.speed = Math.max(0.5, q.speed);
        
        // Optional: slight speed adjustment for better separation (but keep moving)
        if (random.nextBoolean()) {
            p.speed = Math.min(p.speed * 1.1, p.baseSpeed * 1.2); // slight speed up
            q.speed = Math.min(q.speed * 1.1, q.baseSpeed * 1.2);
        }
    }

    private void addEvent(String type, String message) {
        events.addFirst(new Event(type, message));
        while (events.size() > 200) events.removeLast();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32) {
                        b.append(String.format("\\u%04x", (int)c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }
}


