FROM node:22.17.1-alpine3.22 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:22.17.1-alpine3.22
ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY --from=build /workspace/dist ./dist
COPY sql ./sql
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 CMD node -e "fetch('http://127.0.0.1:8080/health/live').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node","dist/server.js"]
