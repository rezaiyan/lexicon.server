package com.alirezaiyan.vokab.server.shared

import java.time.Clock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.CompositeHealth
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class HealthController(
    private val healthEndpoint: HealthEndpoint,
    private val clock: Clock
) {

    @Autowired(required = false)
    private var buildProperties: BuildProperties? = null

    /**
     * Public alias of the actuator readiness group (app state + database), which lives on the
     * internal management port. 503 when not ready, so the Docker healthcheck and the deploy script
     * notice a lost database instead of reading "UP" with "database":"DOWN".
     */
    @GetMapping("/health")
    fun health(
        @RequestHeader(value = "Accept", defaultValue = "application/json") accept: String
    ): ResponseEntity<*> {
        val readiness = healthEndpoint.healthForPath("readiness")
        val status = readiness?.status ?: Status.UNKNOWN
        val dbStatus = ((readiness as? CompositeHealth)?.components?.get("db")?.status ?: Status.UNKNOWN).code
        val httpStatus = if (status == Status.UP) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        val timestamp = Instant.now(clock).toString()
        val version = buildProperties?.version ?: "development"
        val name = buildProperties?.name ?: "vokab-server"
        val uptime = formatUptime(ManagementFactory.getRuntimeMXBean().uptime)

        if (accept.contains("text/html")) {
            return ResponseEntity.status(httpStatus)
                .contentType(MediaType.TEXT_HTML)
                .body(buildHtmlPage(status.code, timestamp, version, name, uptime, dbStatus))
        }

        val healthData = mapOf(
            "status" to status.code,
            "timestamp" to timestamp,
            "version" to version,
            "name" to name,
            "uptime" to uptime,
            "database" to dbStatus
        )
        return ResponseEntity.status(httpStatus).body(ApiResponse(success = status == Status.UP, data = healthData))
    }

    @GetMapping("/version")
    fun version(): ResponseEntity<ApiResponse<Map<String, String>>> {
        val versionData = mapOf(
            "version" to (buildProperties?.version ?: "development"),
            "name" to (buildProperties?.name ?: "vokab-server"),
            "group" to (buildProperties?.group ?: "com.alirezaiyan"),
            "time" to (buildProperties?.time?.toString() ?: Instant.now(clock).toString())
        )
        return ResponseEntity.ok(ApiResponse(success = true, data = versionData))
    }

    private fun formatUptime(ms: Long): String {
        val s = ms / 1000
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    private fun buildHtmlPage(
        status: String, timestamp: String, version: String,
        name: String, uptime: String, dbStatus: String
    ): String {
        val allUp = status == "UP" && dbStatus == "UP"
        val accentColor = if (allUp) "#22c55e" else "#f59e0b"
        val statusText = if (allUp) "All Systems Operational" else "Degraded Performance"
        val dbColor = if (dbStatus == "UP") "#22c55e" else "#ef4444"
        val formattedTime = timestamp.replace("T", " ").substringBefore(".") + " UTC"
        val displayVersion = if (version == "development") version else "v$version"

        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Lexicon Server — Status</title>
    <style>
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Inter', 'Segoe UI', Roboto, sans-serif;
            background: #060a14;
            color: #e2e8f0;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 64px 20px 48px;
            -webkit-font-smoothing: antialiased;
        }

        .brand {
            text-align: center;
            margin-bottom: 48px;
        }
        .brand-icon {
            width: 60px; height: 60px;
            background: linear-gradient(135deg, #4f46e5, #7c3aed);
            border-radius: 18px;
            display: flex; align-items: center; justify-content: center;
            margin: 0 auto 16px;
            font-size: 28px;
            box-shadow: 0 0 48px rgba(99, 102, 241, 0.35);
        }
        .brand-name {
            font-size: 22px; font-weight: 700;
            color: #f8fafc; letter-spacing: -0.4px;
        }
        .brand-tag {
            display: inline-block;
            margin-top: 6px;
            font-size: 10px; font-weight: 600;
            letter-spacing: 2px; text-transform: uppercase;
            color: #475569;
        }

        .status-card {
            background: linear-gradient(160deg, #0d1628 0%, #0f172a 100%);
            border: 1px solid rgba(255,255,255,0.06);
            border-radius: 20px;
            padding: 28px 36px;
            text-align: center;
            margin-bottom: 20px;
            width: 100%; max-width: 520px;
            box-shadow: 0 4px 60px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.04);
        }
        .status-row {
            display: flex; align-items: center; justify-content: center; gap: 14px;
        }
        .dot {
            position: relative; flex-shrink: 0;
            width: 14px; height: 14px; border-radius: 50%;
            background: $accentColor;
        }
        .dot::after {
            content: '';
            position: absolute; inset: -5px;
            border-radius: 50%;
            background: $accentColor;
            opacity: 0;
            animation: ripple 2.4s ease-out infinite;
        }
        @keyframes ripple {
            0%   { transform: scale(0.6); opacity: 0.5; }
            70%  { transform: scale(2.0); opacity: 0; }
            100% { transform: scale(0.6); opacity: 0; }
        }
        .status-label {
            font-size: 19px; font-weight: 700;
            color: $accentColor;
        }
        .status-time {
            margin-top: 10px;
            font-size: 12px; color: #334155; letter-spacing: 0.3px;
        }

        .grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 12px;
            width: 100%; max-width: 520px;
        }

        .card {
            background: linear-gradient(160deg, #0d1628 0%, #0f172a 100%);
            border: 1px solid rgba(255,255,255,0.05);
            border-radius: 14px;
            padding: 22px;
            transition: border-color 0.25s ease;
        }
        .card:hover { border-color: rgba(99, 102, 241, 0.28); }
        .card.wide { grid-column: 1 / -1; }

        .card-icon { font-size: 20px; margin-bottom: 14px; }
        .card-label {
            font-size: 10px; font-weight: 600;
            text-transform: uppercase; letter-spacing: 1.4px;
            color: #334155; margin-bottom: 7px;
        }
        .card-value {
            font-size: 21px; font-weight: 700; color: #f1f5f9;
        }
        .card-value.mono {
            font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace;
            font-size: 15px; letter-spacing: -0.2px;
        }
        .card-sub {
            margin-top: 5px;
            font-size: 11px; color: #334155;
        }
        .badge {
            display: inline-flex; align-items: center; gap: 6px;
        }
        .badge-dot {
            width: 7px; height: 7px; border-radius: 50%;
        }

        .divider {
            width: 100%; max-width: 520px;
            height: 1px; background: rgba(255,255,255,0.04);
            margin: 24px 0;
        }

        .footer {
            font-size: 11px; color: #1e293b;
            text-align: center; letter-spacing: 0.4px;
        }
    </style>
</head>
<body>

    <div class="brand">
        <div class="brand-icon">📚</div>
        <div class="brand-name">Lexicon Server</div>
        <div class="brand-tag">API Status</div>
    </div>

    <div class="status-card">
        <div class="status-row">
            <div class="dot"></div>
            <div class="status-label">$statusText</div>
        </div>
        <div class="status-time">Last updated &nbsp;·&nbsp; $formattedTime</div>
    </div>

    <div class="grid">

        <div class="card">
            <div class="card-icon">⚡</div>
            <div class="card-label">API</div>
            <div class="card-value badge">
                <span class="badge-dot" style="background:#22c55e"></span>
                $status
            </div>
            <div class="card-sub">HTTP / REST</div>
        </div>

        <div class="card">
            <div class="card-icon">🗄️</div>
            <div class="card-label">Database</div>
            <div class="card-value badge">
                <span class="badge-dot" style="background:$dbColor"></span>
                <span style="color:$dbColor">$dbStatus</span>
            </div>
            <div class="card-sub">PostgreSQL</div>
        </div>

        <div class="card">
            <div class="card-icon">⏱️</div>
            <div class="card-label">Uptime</div>
            <div class="card-value">$uptime</div>
            <div class="card-sub">Since last deploy</div>
        </div>

        <div class="card">
            <div class="card-icon">🏷️</div>
            <div class="card-label">Version</div>
            <div class="card-value mono">$displayVersion</div>
            <div class="card-sub">$name</div>
        </div>

        <div class="card wide">
            <div class="card-icon">🌐</div>
            <div class="card-label">Server Time (UTC)</div>
            <div class="card-value mono">$formattedTime</div>
            <div class="card-sub">Coordinated Universal Time</div>
        </div>

    </div>

    <div class="divider"></div>
    <div class="footer">Lexicon Vocabulary Learning · vokab.alirezaiyan.com</div>

</body>
</html>"""
    }
}
