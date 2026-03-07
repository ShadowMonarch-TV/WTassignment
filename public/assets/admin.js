const token = localStorage.getItem("ds_token");
const role = (localStorage.getItem("ds_role") || "").toLowerCase();
const name = localStorage.getItem("ds_name") || "Admin";

if (!token || role !== "admin") {
  window.location.href = "/index.html";
}

document.getElementById("adminWelcome").textContent = `Logged in as ${name}`;
document.getElementById("logoutBtn").addEventListener("click", () => {
  localStorage.removeItem("ds_token");
  localStorage.removeItem("ds_user");
  localStorage.removeItem("ds_name");
  localStorage.removeItem("ds_role");
  window.location.href = "/index.html";
});

loadAll();

function headers() {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`
  };
}

async function loadAll() {
  await Promise.all([loadSummary(), loadSettings(), loadQuestions(), loadUsers(), loadAttempts()]);
}

async function loadSummary() {
  const response = await fetch("/api/admin/summary", { headers: { Authorization: `Bearer ${token}` } });
  const data = await response.json();
  if (!response.ok) return;

  document.getElementById("summaryCards").innerHTML = `
    <article class="summary-card"><p class="k">Students</p><p class="v">${data.totalUsers}</p></article>
    <article class="summary-card"><p class="k">Questions</p><p class="v">${data.totalQuestions}</p></article>
    <article class="summary-card"><p class="k">Attempts</p><p class="v">${data.totalAttempts}</p></article>
    <article class="summary-card"><p class="k">Avg %</p><p class="v">${Number(data.averagePercentage).toFixed(2)}</p></article>
    <article class="summary-card"><p class="k">Top Score</p><p class="v">${data.highestScore}</p></article>
  `;
}

document.getElementById("exportUsersBtn").addEventListener("click", () => {
  downloadCsv("/api/admin/export/users.csv", "users.csv");
});

document.getElementById("exportAttemptsBtn").addEventListener("click", () => {
  downloadCsv("/api/admin/export/attempts.csv", "attempts.csv");
});

async function loadSettings() {
  const response = await fetch("/api/admin/settings", { headers: { Authorization: `Bearer ${token}` } });
  const data = await response.json();
  if (!response.ok) return;

  document.getElementById("questionTimeSeconds").value = data.questionTimeSeconds;
  document.getElementById("maxAttemptsPerUser").value = data.maxAttemptsPerUser;
  document.getElementById("shuffleQuestions").checked = data.shuffleQuestions;
  document.getElementById("showCorrectAnswers").checked = data.showCorrectAnswers;
}

document.getElementById("settingsForm").addEventListener("submit", async (event) => {
  event.preventDefault();

  const payload = {
    questionTimeSeconds: Number(document.getElementById("questionTimeSeconds").value),
    maxAttemptsPerUser: Number(document.getElementById("maxAttemptsPerUser").value),
    shuffleQuestions: document.getElementById("shuffleQuestions").checked,
    showCorrectAnswers: document.getElementById("showCorrectAnswers").checked
  };

  const response = await fetch("/api/admin/settings", {
    method: "PUT",
    headers: headers(),
    body: JSON.stringify(payload)
  });
  const data = await response.json();
  const msg = document.getElementById("settingsMsg");
  msg.textContent = data.message || data.error || "Done";
  msg.className = response.ok ? "status success" : "status error";
});

function questionPayload() {
  return {
    id: Number(document.getElementById("qId").value),
    question: document.getElementById("qText").value.trim(),
    options: [
      document.getElementById("qOpt0").value.trim(),
      document.getElementById("qOpt1").value.trim(),
      document.getElementById("qOpt2").value.trim(),
      document.getElementById("qOpt3").value.trim()
    ],
    correctOptionIndex: Number(document.getElementById("qCorrect").value)
  };
}

async function sendQuestion(method, payload) {
  const response = await fetch("/api/admin/questions", {
    method,
    headers: headers(),
    body: JSON.stringify(payload)
  });
  const data = await response.json();
  const msg = document.getElementById("questionMsg");
  msg.textContent = data.message || data.error || "Done";
  msg.className = response.ok ? "status success" : "status error";
  if (response.ok) {
    await loadQuestions();
  }
}

document.getElementById("createQuestionBtn").addEventListener("click", () => sendQuestion("POST", questionPayload()));
document.getElementById("updateQuestionBtn").addEventListener("click", () => sendQuestion("PUT", questionPayload()));
document.getElementById("deleteQuestionBtn").addEventListener("click", async () => {
  const id = Number(document.getElementById("qId").value);
  await sendQuestion("DELETE", { id });
});

async function loadQuestions() {
  const response = await fetch("/api/admin/questions", { headers: { Authorization: `Bearer ${token}` } });
  const data = await response.json();
  if (!response.ok) return;
  const rows = (data.questions || []).map((q) => `
    <tr>
      <td>${q.id}</td>
      <td>${escapeHtml(q.question)}</td>
      <td>${q.correctOptionIndex}</td>
    </tr>
  `).join("");

  document.getElementById("questionsTableWrap").innerHTML = `
    <table class="leaderboard-table">
      <thead><tr><th>ID</th><th>Question</th><th>Correct</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

document.getElementById("updateRoleBtn").addEventListener("click", async () => {
  const username = document.getElementById("manageUsername").value.trim();
  const role = document.getElementById("manageRole").value;

  const response = await fetch("/api/admin/users/manage", {
    method: "PUT",
    headers: headers(),
    body: JSON.stringify({ username, role })
  });
  const data = await response.json();
  setMessage("userManageMsg", data.message || data.error || "Done", response.ok);
  if (response.ok) {
    await loadUsers();
    await loadSummary();
  }
});

document.getElementById("deleteUserBtn").addEventListener("click", async () => {
  const username = document.getElementById("manageUsername").value.trim();

  const response = await fetch("/api/admin/users/manage", {
    method: "DELETE",
    headers: headers(),
    body: JSON.stringify({ username })
  });
  const data = await response.json();
  setMessage("userManageMsg", data.message || data.error || "Done", response.ok);
  if (response.ok) {
    await loadUsers();
    await loadAttempts();
    await loadSummary();
  }
});

async function loadUsers() {
  const response = await fetch("/api/admin/users", { headers: { Authorization: `Bearer ${token}` } });
  const data = await response.json();
  if (!response.ok) return;

  const rows = (data.users || []).map((u) => `
    <tr>
      <td>${escapeHtml(u.fullName)}</td>
      <td>${escapeHtml(u.username)}</td>
      <td>${escapeHtml(u.role)}</td>
      <td>${new Date(u.createdAt).toLocaleString()}</td>
    </tr>
  `).join("");

  document.getElementById("usersTableWrap").innerHTML = `
    <table class="leaderboard-table">
      <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Created</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

document.getElementById("resetAttemptsBtn").addEventListener("click", async () => {
  const username = document.getElementById("resetUsername").value.trim();
  const response = await fetch("/api/admin/attempts/reset", {
    method: "POST",
    headers: headers(),
    body: JSON.stringify({ username })
  });
  const data = await response.json();
  setMessage("attemptResetMsg", `${data.message || data.error || "Done"}${response.ok ? ` (${data.deleted} deleted)` : ""}`, response.ok);
  if (response.ok) {
    await loadAttempts();
    await loadSummary();
  }
});

async function loadAttempts() {
  const response = await fetch("/api/admin/attempts", { headers: { Authorization: `Bearer ${token}` } });
  const data = await response.json();
  if (!response.ok) return;

  const rows = (data.attempts || []).map((a) => `
    <tr>
      <td>${escapeHtml(a.fullName)}</td>
      <td>${a.score}/${a.total}</td>
      <td>${Number(a.percentage).toFixed(2)}%</td>
      <td>${new Date(a.submittedAt).toLocaleString()}</td>
    </tr>
  `).join("");

  document.getElementById("attemptsTableWrap").innerHTML = `
    <table class="leaderboard-table">
      <thead><tr><th>Student</th><th>Score</th><th>Percentage</th><th>Submitted At</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function setMessage(id, text, ok) {
  const el = document.getElementById(id);
  el.textContent = text;
  el.className = ok ? "status success" : "status error";
}

async function downloadCsv(url, filename) {
  const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) {
    const data = await response.json();
    alert(data.error || "Export failed.");
    return;
  }
  const blob = await response.blob();
  const blobUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = blobUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(blobUrl);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
