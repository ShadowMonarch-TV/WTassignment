const token = localStorage.getItem("ds_token");
const role = (localStorage.getItem("ds_role") || "student").toLowerCase();

if (!token || role !== "student") {
  window.location.href = "/index.html";
}

document.getElementById("quizBtn").addEventListener("click", () => {
  window.location.href = "/quiz.html";
});

document.getElementById("logoutBtn").addEventListener("click", () => {
  localStorage.removeItem("ds_token");
  localStorage.removeItem("ds_user");
  localStorage.removeItem("ds_name");
  localStorage.removeItem("ds_role");
  window.location.href = "/index.html";
});

boot();

async function boot() {
  await loadProfile();
  await loadAttempts();
}

async function loadProfile() {
  const response = await fetch("/api/me", { headers: { Authorization: `Bearer ${token}` } });
  const data = await response.json();
  if (!response.ok) return;

  document.getElementById("profileMeta").textContent = `${data.username} • joined ${new Date(data.createdAt).toLocaleDateString()}`;
  document.getElementById("fullName").value = data.fullName || "";
  localStorage.setItem("ds_name", data.fullName || data.username);
}

document.getElementById("profileForm").addEventListener("submit", async (event) => {
  event.preventDefault();

  const fullName = document.getElementById("fullName").value.trim();
  const response = await fetch("/api/me", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({ fullName })
  });
  const data = await response.json();

  const msg = document.getElementById("profileMsg");
  msg.textContent = data.message || data.error || "Done";
  msg.className = response.ok ? "status success" : "status error";

  if (response.ok) {
    localStorage.setItem("ds_name", fullName);
    await loadProfile();
  }
});

async function loadAttempts() {
  const response = await fetch("/api/me/attempts", { headers: { Authorization: `Bearer ${token}` } });
  const data = await response.json();
  if (!response.ok) return;

  const attempts = data.attempts || [];
  if (!attempts.length) {
    document.getElementById("myAttemptsWrap").innerHTML = "<p class='status'>No attempts yet.</p>";
    return;
  }

  const rows = attempts.map((a) => `
    <tr>
      <td>${a.id}</td>
      <td>${a.score}/${a.total}</td>
      <td>${Number(a.percentage).toFixed(2)}%</td>
      <td>${new Date(a.submittedAt).toLocaleString()}</td>
    </tr>
  `).join("");

  document.getElementById("myAttemptsWrap").innerHTML = `
    <table class="leaderboard-table">
      <thead><tr><th>ID</th><th>Score</th><th>Percentage</th><th>Submitted At</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}
