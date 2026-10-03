#!/bin/bash

# Generate Secrets for Vokab Server
# This script generates secure random secrets for deployment

echo "🔐 Generating Secure Secrets for Vokab Server"
echo "=============================================="
echo ""

# Generate webhook secret
echo "🔗 REVENUECAT_WEBHOOK_SECRET:"
openssl rand -hex 32
echo ""

echo "✅ Secrets generated!"
echo ""
echo "⚠️  IMPORTANT REMINDERS:"
echo "  1. Copy these secrets to your environment variables"
echo "  2. NEVER commit these secrets to Git"
echo "  3. Store them securely (password manager recommended)"
echo "  4. Rotate secrets periodically (every 3-6 months)"
echo ""
echo "📋 Other Required Keys (Get from providers):"
echo "  - GOOGLE_CLIENT_ID: https://console.cloud.google.com/apis/credentials"
echo "  - OPENROUTER_API_KEY: https://openrouter.ai/keys"
echo "  - REVENUECAT_API_KEY: https://app.revenuecat.com/"
echo ""


