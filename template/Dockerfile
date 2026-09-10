# Stage 1: Build the static files
FROM node:18-alpine AS build

WORKDIR /app

# Copy package files first (better caching)
COPY package.json package-lock.json* ./

# Install dependencies
RUN npm install

# Copy the rest of your app code
COPY . .

# Build the production static files
RUN npm run build

# Stage 2: Serve the built files with Nginx
FROM nginx:alpine

# Copy built static files from the build stage into Nginx's serving directory
COPY --from=build /app/dist /usr/share/nginx/html

# Nginx serves on port 80 by default
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
