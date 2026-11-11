FROM node:22-alpine
WORKDIR /app
COPY --chown=node:node . .
RUN mkdir -p /data && chown node:node /data
USER node
ENV LISTEN_HOST=0.0.0.0 LISTEN_PORT=8787 LISTEN_DATA_DIR=/data
EXPOSE 8787
VOLUME ["/data"]
CMD ["node", "server.js"]
