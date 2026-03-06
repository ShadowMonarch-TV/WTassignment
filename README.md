# Data Science MCQ App

Production-ready student assessment app with:
- Modern responsive UI (login, registration, quiz)
- Java backend API
- PostgreSQL database integration
- Render deployment blueprint

## Tech Stack
- Frontend: HTML, modern CSS, vanilla JavaScript
- Backend: Java 17, `com.sun.net.httpserver`
- DB: PostgreSQL
- Build: Maven (fat JAR)

## API Endpoints
- `POST /api/register`
- `POST /api/login`
- `GET /api/questions` (Bearer token)
- `POST /api/submit` (Bearer token)
- `GET /api/health`

## Local Run
1. Set database URL in shell:

```powershell
$env:DATABASE_URL="postgres://username:password@localhost:5432/datascience_quiz"
```

2. Build:

```powershell
mvn -DskipTests package
```

3. Start app:

```powershell
java -jar target/ds-quiz-app.jar
```

4. Open:
- `http://localhost:8080/index.html`

## Render Deployment
`render.yaml` is included for Blueprint deploy with web service + managed PostgreSQL.

1. Push this project to GitHub/GitLab/Bitbucket.
2. In Render Dashboard, create from Blueprint (`render.yaml`).
3. Apply the plan and deploy.

Render URL pattern after deploy:
- `https://<your-service>.onrender.com`
