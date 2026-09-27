FROM node:22.20.0-alpine AS deps
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
FROM node:22.20.0-alpine
RUN addgroup -S relay && adduser -S relay -G relay
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY package.json index.mjs ./
USER relay
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1
CMD ["node","index.mjs"]
