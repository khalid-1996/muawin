# Server: MongoDB + car-api

MongoDB for your projects, plus `car-api`, which receives المعاون's driving data
(today's kilometres and one location point per minute while moving).

## 1. Deploy in Portainer

1. **Stacks → Add stack → Repository**
   - URL: `https://github.com/khalid-1996/muawin`
   - Compose path: `server/docker-compose.yml`
2. **Environment variables:**
   | Name | Value |
   |---|---|
   | `MONGO_ROOT_USER` | `admin` |
   | `MONGO_ROOT_PASSWORD` | a long random password |
   | `CAR_API_KEY` | a long random key (at least 16 characters) |

   Generate random values with: `openssl rand -hex 24`
3. **Deploy the stack.**

MongoDB only listens on `127.0.0.1:27017` of the server; it is not reachable from the internet.
`car-api` listens on port **8090**.

## 2. HTTPS with Nginx Proxy Manager

Add a Proxy Host, for example `car.khalid96.net` → `http://<server-ip>:8090`, then request an SSL certificate.

Check it: open `https://car.khalid96.net/health` and you should see `{"ok":true}`.

## 3. Connect المعاون

On the car: long-press the clock → Settings → **السيرفر (MongoDB)**:
- Server URL: `https://car.khalid96.net`
- API key: the `CAR_API_KEY` value

Tap **حفظ والمزامنة الآن**. The status line shows the last successful sync and how many points are still waiting.
Drives without internet are kept on the car and uploaded later.

## Data

Database `muawin`:

- **days**: one document per day, for example
  `{ _id: "leopard5:2026-10-27", date: "2026-10-27", km: 42.7, points: 38, firstAt, lastAt, updatedAt }`
- **points**: one document per minute while moving, for example
  `{ _id: "leopard5:<ts>", date, ts, lat, lon, kmh, acc, alt, loc: GeoJSON }`.
  It has a `2dsphere` index on `loc`, for map and area queries later.

### API (header `X-API-Key: <CAR_API_KEY>`)

- `GET /api/car/days?from=2026-10-01&to=2026-10-31`
- `GET /api/car/days/2026-10-27/points`

## Using MongoDB in other projects

- **Containers on the same server:** join the `mongo` network and connect with
  `mongodb://admin:<password>@mongo:27017/?authSource=admin`
- **From your computer:** use an SSH tunnel, for example
  `ssh -L 27017:127.0.0.1:27017 <server>`, then connect MongoDB Compass to `mongodb://admin:<password>@127.0.0.1:27017/?authSource=admin`
