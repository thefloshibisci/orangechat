FROM node:22-alpine
WORKDIR /app
COPY --chown=node:node . .
RUN mkdir -p /data && chown node:node /data
USER node
ENV LISTEN_HOST=0.0.0.0 LISTEN_PORT=8787 LISTEN_DATA_DIR=/data
EXPOSE 8787
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "const net=require('net');const s=net.connect(Number(process.env.LISTEN_PORT||8787),'127.0.0.1',()=>{s.end();process.exit(0)});s.on('error',()=>process.exit(1));setTimeout(()=>process.exit(1),3000)"
CMD ["node", "server.js"]
