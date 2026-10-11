#!/bin/bash

# Start development server with H2 database
echo "🚀 Starting Vokab Server in development mode..."
echo ""

# Stop any running service first
echo "🛑 Checking for running services..."

# 1. Check and kill process using PID file
if [ -f server.pid ]; then
    PID=$(cat server.pid)
    if ps -p $PID > /dev/null 2>&1; then
        echo "   • Stopping server (PID: $PID)..."
        kill $PID 2>/dev/null || kill -9 $PID 2>/dev/null
        sleep 2
        echo "   ✓ Server stopped"
    fi
    rm -f server.pid
fi

# 2. Check and kill any process using the port
PORT=${PORT:-8080}
if lsof -Pi :$PORT -sTCP:LISTEN -t > /dev/null 2>&1; then
    echo "   • Port $PORT is in use, stopping process..."
    lsof -ti:$PORT | xargs kill -9 2>/dev/null
    sleep 1
    echo "   ✓ Port $PORT freed"
fi

# 3. Stop Gradle daemon (optional, for clean restart)
if pgrep -f "GradleDaemon" > /dev/null 2>&1; then
    echo "   • Stopping Gradle daemon for clean restart..."
    ./gradlew --stop > /dev/null 2>&1
    sleep 1
    echo "   ✓ Gradle daemon stopped"
fi

echo "✅ All running services stopped"
echo ""

# Check if .env file exists
if [ ! -f .env ]; then
    echo "⚠️  .env file not found. Creating from template..."
    cp env.example .env
    echo "✅ Created .env file. Please update it with your API keys."
    echo ""
fi

# Load environment variables
if [ -f .env ]; then
    # Sourced, not word-split, so values with spaces (e.g. EMAIL_FROM_ADDRESS) survive
    set -a; . ./.env; set +a
fi

# Use H2 profile when using default H2 DB so Flyway runs H2-compatible migrations
if [ -z "$DATABASE_URL" ] || [[ "$DATABASE_URL" == *"h2"* ]]; then
    export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-h2}"
fi

# Check required variables
if [ -z "$OPENROUTER_API_KEY" ] || [ "$OPENROUTER_API_KEY" = "your-openrouter-api-key" ]; then
    echo "⚠️  Warning: OPENROUTER_API_KEY not set in .env"
    echo "   Get your API key from: https://openrouter.ai/keys"
    echo ""
fi

if [ -z "$GOOGLE_CLIENT_ID" ] || [ "$GOOGLE_CLIENT_ID" = "your-google-client-id.apps.googleusercontent.com" ]; then
    echo "⚠️  Warning: GOOGLE_CLIENT_ID not set in .env"
    echo "   Get credentials from: https://console.cloud.google.com/apis/credentials"
    echo ""
fi

echo "📊 Configuration:"
echo "   Database: H2 (in-memory)"
echo "   Port: ${PORT:-8080}"
echo "   OpenRouter: ${OPENROUTER_API_KEY:0:20}..."
echo "   Google OAuth: ${GOOGLE_CLIENT_ID:0:30}..."
echo ""

# Start the server
echo "🏗️  Building and starting server..."
./gradlew bootRun

echo ""
echo "✅ Server started successfully!"
echo "   Health check: http://localhost:${PORT:-8080}/api/v1/health"
echo "   H2 Console: http://localhost:${PORT:-8080}/h2-console"
echo ""

