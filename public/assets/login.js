const form = document.getElementById("loginForm");
const statusMsg = document.getElementById("statusMsg");
const loginBtn = document.getElementById("loginBtn");

const token = localStorage.getItem("ds_token");
if (token) {
  window.location.href = "/quiz.html";
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();

  const username = document.getElementById("username").value.trim();
  const password = document.getElementById("password").value;

  if (!username || !password) {
    setStatus("Please enter username and password.", true);
    return;
  }

  loginBtn.disabled = true;
  loginBtn.textContent = "Signing in...";

  try {
    const response = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password })
    });

    const data = await response.json();
    if (!response.ok || !data.success) {
      setStatus(data.message || data.error || "Login failed.", true);
      return;
    }

    localStorage.setItem("ds_token", data.token);
    localStorage.setItem("ds_user", data.username);
    localStorage.setItem("ds_name", data.fullName || data.username);
    window.location.href = "/quiz.html";
  } catch (error) {
    setStatus("Server error. Please try again.", true);
  } finally {
    loginBtn.disabled = false;
    loginBtn.textContent = "Login";
  }
});

function setStatus(message, isError) {
  statusMsg.innerHTML = `${escapeHtml(message)} ${isError ? "" : '<a href="/register.html">Create account</a>'}`;
  statusMsg.classList.toggle("error", isError);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
