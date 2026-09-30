#!/usr/bin/env bash
# =====================================================================================
#  Cab Eye Production AWS Deployment Script
#  Deploys: CloudFormation Stack, Docker Build, ECR Push, Container Run
# =====================================================================================

set -e

PROFILE="${AWS_PROFILE:-cab-eye}"
REGION="${AWS_REGION:-ap-south-1}"
STACK_NAME="cabeye-production"

echo "================================================================================"
echo " Cab Eye AWS Production Deployment"
echo " Profile: $PROFILE | Region: $REGION | Stack: $STACK_NAME"
echo "================================================================================"

# 1. Verify AWS Identity
echo "[1/5] Verifying AWS Caller Identity..."
aws sts get-caller-identity --profile "$PROFILE" --region "$REGION"

# 2. Deploy CloudFormation Stack
echo "[2/5] Deploying CloudFormation Stack (VPC, Subnets, RDS, Redis, ALB, ECR)..."
DB_PASS="${CABEYE_DB_PASSWORD:-CabEyeProdDbSecure123}"

aws cloudformation deploy \
    --template-file aws/cloudformation.yml \
    --stack-name "$STACK_NAME" \
    --parameter-overrides DBPassword="$DB_PASS" \
    --capabilities CAPABILITY_IAM \
    --profile "$PROFILE" \
    --region "$REGION"

# 3. Retrieve Outputs
echo "[3/5] Retrieving CloudFormation Stack Outputs..."
ECR_URI=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --query "Stacks[0].Outputs[?OutputKey=='ECRRepoUri'].OutputValue" --output text --profile "$PROFILE" --region "$REGION")
ALB_DNS=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --query "Stacks[0].Outputs[?OutputKey=='ALBDNSName'].OutputValue" --output text --profile "$PROFILE" --region "$REGION")
RDS_HOST=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --query "Stacks[0].Outputs[?OutputKey=='RDSEndpoint'].OutputValue" --output text --profile "$PROFILE" --region "$REGION")
REDIS_HOST=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --query "Stacks[0].Outputs[?OutputKey=='RedisEndpoint'].OutputValue" --output text --profile "$PROFILE" --region "$REGION")

echo "  > ECR Repository: $ECR_URI"
echo "  > ALB DNS Name:   $ALB_DNS"
echo "  > RDS PostgreSQL: $RDS_HOST"
echo "  > Redis Endpoint: $REDIS_HOST"

# 4. Build and Push Docker Image to ECR
echo "[4/5] Building and Pushing Docker Image to ECR..."
echo "  > Compiling bootJar locally..."
(cd backend && ./gradlew bootJar)

echo "  > Building Docker container..."
docker build -t "$ECR_URI:latest" backend/

echo "  > Logging into AWS ECR..."
aws ecr get-login-password --region "$REGION" --profile "$PROFILE" | docker login --username AWS --password-stdin "$ECR_URI"

echo "  > Pushing image to ECR..."
docker push "$ECR_URI:latest"

# 5. Summary and Verification
echo "================================================================================"
echo " DEPLOYMENT READY!"
echo "================================================================================"
echo "ALB Public Endpoint:  http://$ALB_DNS"
echo "Health Check URL:     http://$ALB_DNS/health"
echo "Readiness Check URL:  http://$ALB_DNS/ready"
echo "WebSocket URL:        ws://$ALB_DNS/ws/ride"
echo ""
echo "Android BuildConfig parameters:"
echo "  BASE_URL = \"http://$ALB_DNS/\""
echo "  WS_URL   = \"ws://$ALB_DNS/ws/ride\""
echo "================================================================================"
