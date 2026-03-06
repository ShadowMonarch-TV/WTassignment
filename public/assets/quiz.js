const quizContainer = document.getElementById("quizContainer");
const resultCard = document.getElementById("resultCard");
const logoutBtn = document.getElementById("logoutBtn");
const welcomeText = document.getElementById("welcomeText");

const token = localStorage.getItem("ds_token");
const username = localStorage.getItem("ds_user") || "student";
const fullName = localStorage.getItem("ds_name") || username;

const QUESTION_TIME_SECONDS = 30;

let questions = [];
let currentIndex = 0;
let selectedAnswer = null;
let answers = [];
let timer = null;
let timeLeft = QUESTION_TIME_SECONDS;

if (!token) {
  window.location.href = "/index.html";
}

welcomeText.textContent = `Logged in as ${fullName}`;

logoutBtn.addEventListener("click", () => {
  clearInterval(timer);
  localStorage.removeItem("ds_token");
  localStorage.removeItem("ds_user");
  localStorage.removeItem("ds_name");
  window.location.href = "/index.html";
});

loadQuestions();

async function loadQuestions() {
  try {
    const response = await fetch("/api/questions", {
      headers: { Authorization: `Bearer ${token}` }
    });

    if (response.status === 401) {
      logoutBtn.click();
      return;
    }

    const data = await response.json();
    questions = data.questions || [];

    if (!questions.length) {
      quizContainer.innerHTML = "<p class='status error'>No questions available.</p>";
      return;
    }

    answers = new Array(questions.length).fill(-1);
    currentIndex = 0;
    renderQuestion();
  } catch (error) {
    quizContainer.innerHTML = "<p class='status error'>Could not load questions.</p>";
  }
}

function renderQuestion() {
  const q = questions[currentIndex];
  selectedAnswer = answers[currentIndex] >= 0 ? answers[currentIndex] : null;
  timeLeft = QUESTION_TIME_SECONDS;

  const progress = `${currentIndex + 1} / ${questions.length}`;
  const optionsHtml = q.options
    .map((option, optionIndex) => {
      const optionLabel = String.fromCharCode(65 + optionIndex);
      const checked = selectedAnswer === optionIndex ? "checked" : "";
      return `
        <label class="option">
          <input type="radio" name="current-question" value="${optionIndex}" ${checked} />
          <span><strong>${optionLabel})</strong> ${escapeHtml(option)}</span>
        </label>
      `;
    })
    .join("");

  quizContainer.innerHTML = `
    <article class="question-card active-question">
      <div class="question-meta">
        <p class="question-progress">Question ${progress}</p>
        <div class="timer" id="timerBadge">Time left: ${timeLeft}s</div>
      </div>
      <p class="question-title">${q.id}. ${escapeHtml(q.question)}</p>
      <div class="options" id="optionsWrap">${optionsHtml}</div>
      <div class="submit-wrap quiz-nav">
        <button type="button" class="btn-outline" id="nextBtn">${currentIndex === questions.length - 1 ? "Finish Quiz" : "Next Question"}</button>
      </div>
    </article>
  `;

  const optionsWrap = document.getElementById("optionsWrap");
  optionsWrap.addEventListener("change", (event) => {
    const target = event.target;
    if (target && target.name === "current-question") {
      selectedAnswer = Number(target.value);
    }
  });

  document.getElementById("nextBtn").addEventListener("click", moveNext);

  startTimer();
}

function startTimer() {
  clearInterval(timer);
  const timerBadge = document.getElementById("timerBadge");

  timer = setInterval(() => {
    timeLeft -= 1;

    if (timerBadge) {
      timerBadge.textContent = `Time left: ${timeLeft}s`;
      if (timeLeft <= 10) {
        timerBadge.classList.add("danger");
      }
    }

    if (timeLeft <= 0) {
      clearInterval(timer);
      moveNext();
    }
  }, 1000);
}

function moveNext() {
  clearInterval(timer);

  if (selectedAnswer !== null && selectedAnswer >= 0) {
    answers[currentIndex] = selectedAnswer;
  }

  if (currentIndex >= questions.length - 1) {
    submitQuiz();
    return;
  }

  currentIndex += 1;
  renderQuestion();
}

async function submitQuiz() {
  try {
    const response = await fetch("/api/submit", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({ answers })
    });

    if (response.status === 401) {
      logoutBtn.click();
      return;
    }

    const data = await response.json();
    if (!response.ok) {
      alert(data.error || "Submission failed.");
      return;
    }

    renderResult(data);
    loadLeaderboard();
  } catch (error) {
    alert("Submission failed due to server error.");
  }
}

function renderResult(result) {
  quizContainer.classList.add("hidden");
  resultCard.classList.remove("hidden");

  const chips = result.results
    .map((isCorrect, index) => {
      const cls = isCorrect ? "ok" : "bad";
      const text = isCorrect ? "Correct" : "Wrong";
      return `<span class="result-chip ${cls}">Q${index + 1}: ${text}</span>`;
    })
    .join("");

  resultCard.innerHTML = `
    <h2>${escapeHtml(fullName)}, your result</h2>
    <p><strong>Score:</strong> ${result.score} / ${result.total}</p>
    <p><strong>Percentage:</strong> ${result.percentage.toFixed(2)}%</p>
    <div>${chips}</div>
    <section class="leaderboard-wrap">
      <h3>Leaderboard</h3>
      <div id="leaderboardBody" class="status">Loading leaderboard...</div>
    </section>
  `;
}

async function loadLeaderboard() {
  const mount = document.getElementById("leaderboardBody");
  if (!mount) return;

  try {
    const response = await fetch("/api/leaderboard", {
      headers: { Authorization: `Bearer ${token}` }
    });

    if (response.status === 401) {
      logoutBtn.click();
      return;
    }

    const data = await response.json();
    const leaderboard = data.leaderboard || [];

    if (!leaderboard.length) {
      mount.textContent = "No participants yet.";
      return;
    }

    const rows = leaderboard
      .map((entry, index) => {
        return `
          <tr>
            <td>${index + 1}</td>
            <td>${escapeHtml(entry.fullName || entry.username)}</td>
            <td>${entry.score}/${entry.total}</td>
            <td>${Number(entry.percentage).toFixed(2)}%</td>
          </tr>
        `;
      })
      .join("");

    mount.innerHTML = `
      <table class="leaderboard-table">
        <thead>
          <tr>
            <th>Rank</th>
            <th>Student</th>
            <th>Score</th>
            <th>Percentage</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    `;
  } catch (error) {
    mount.textContent = "Could not load leaderboard.";
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
