const form = document.getElementById("registerForm");
const registerBtn = document.getElementById("registerBtn");
const registerMsg = document.getElementById("registerMsg");

form.addEventListener("submit", async (event) => {
  event.preventDefault();

  const fullName = document.getElementById("fullName").value.trim();
  const username = document.getElementById("username").value.trim();
  const password = document.getElementById("password").value;

  if (!fullName || !username || !password) {
    setStatus("Please fill all fields.", true);
    return;
  }

  registerBtn.disabled = true;
  registerBtn.textContent = "Creating account...";

  try {
    const response = await fetch("/api/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fullName, username, password })
    });

    const data = await response.json();
    if (!response.ok) {
      setStatus(data.error || "Registration failed.", true);
      return;
    }

    setStatus("Registration successful. Redirecting to login...", false, true);
    setTimeout(() => {
      window.location.href = "/index.html";
    }, 1200);
  } catch (error) {
    setStatus("Server error. Please try again.", true);
  } finally {
    registerBtn.disabled = false;
    registerBtn.textContent = "Create Account";
  }
});

function setStatus(message, isError, isSuccess = false) {
  registerMsg.textContent = message;
  registerMsg.classList.toggle("error", isError);
  registerMsg.classList.toggle("success", isSuccess);
}
