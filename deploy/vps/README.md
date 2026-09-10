# Ludo Mada VPS Deployment

Target:
- Domain: api.ludomada.com
- App directory: /opt/LudoMada
- Node.js: /usr/bin/node
- API: 127.0.0.1:3000
- Reverse proxy: Nginx
- Database: PostgreSQL

Deployment order:
1. Provision VPS
2. Install Node.js, PostgreSQL and Nginx
3. Create user `ludomada`
4. Copy application to `/opt/LudoMada`
5. Create `/opt/LudoMada/.env.production`
6. Build application
7. Deploy Prisma migrations as a separate deployment step
8. Install `ludomada.service`
9. Enable/start the service
10. Install Nginx configuration
11. Verify `/health` and `/ready`
12. Configure DNS for `api.ludomada.com`
13. Obtain Let's Encrypt certificate
14. Enforce HTTPS
