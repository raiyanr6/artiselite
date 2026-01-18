# Use Node.js (Lightweight version)
FROM node:18-alpine

# Set the working directory inside the container
WORKDIR /app

# Copy package files first (better caching)
COPY package.json package-lock.json* ./

# Install dependencies
RUN npm install

# Copy the rest of your app code
COPY . .

# Expose the Vite port
EXPOSE 5173

# Start the app
CMD ["npm", "run", "dev"]