# syntax=docker/dockerfile:1.7
FROM node:25-alpine AS build
WORKDIR /app
COPY package*.json ./

# Cache mount on /root/.npm so `npm ci` finds previously-downloaded
# tarballs instead of re-fetching the entire dep tree on every rebuild
# where package-lock.json has changed even slightly.
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY . .
RUN npm run build

FROM node:25-alpine
RUN addgroup -S kosha && adduser -S kosha -G kosha
USER kosha
WORKDIR /app
COPY --from=build /app/build ./build
COPY --from=build /app/package.json .
COPY --from=build /app/node_modules ./node_modules
EXPOSE 3000
ENV PORT=3000
CMD ["node", "build"]
