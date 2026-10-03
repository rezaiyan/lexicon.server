#!/bin/bash

# Start production server with PostgreSQL
echo "🚀 Starting Lexicon Server in production mode..."
echo ""

# Check if .env file exists
if [ ! -f .env ]; then
    echo "❌ Error: .env file not found"
    echo "   Please create .env file with production configuration"
    exit 1
fi

# Load environment variables
export $(cat .env | grep -v '^#' | xargs)

# Validate required variables
REQUIRED_VARS=(
    "DATABASE_URL"
    "DATABASE_USERNAME"
    "DATABASE_PASSWORD"
    "GOOGLE_CLIENT_ID"
    "OPENROUTER_API_KEY"
)

for var in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        echo "❌ Error: $var is not set in .env"
        exit 1
    fi
done

echo "📊 Production Configuration:"
echo "   Database: PostgreSQL"
echo "   Port: ${PORT:-8080}"
echo "   CORS Origins: $CORS_ALLOWED_ORIGINS"
echo ""

# Build the application
echo "🏗️  Building application..."
./gradlew clean build -x test

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

# Start the server
echo "🚀 Starting production server..."
java -jar build/libs/lexicon.server-0.0.1-SNAPSHOT.jar

echo ""
echo "✅ Server started successfully!"
echo "   Health check: http://localhost:${PORT:-8080}/api/v1/health"
echo ""

