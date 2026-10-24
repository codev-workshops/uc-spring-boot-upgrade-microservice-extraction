# syntax=docker/dockerfile:1
# Next.js frontend. NEXT_PUBLIC_* values are inlined into the browser bundle by `next build`,
# so the public backend URL is a build argument, not runtime configuration.
FROM node:16-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm install
COPY . .
ARG NEXT_PUBLIC_SERVER_BASE_URL=/api
ENV NEXT_PUBLIC_SERVER_BASE_URL=${NEXT_PUBLIC_SERVER_BASE_URL}
RUN npm run build

FROM node:16-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build --chown=node:node /app/package.json /app/package-lock.json ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/.next ./.next
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 3000
CMD ["npm", "run", "start"]
