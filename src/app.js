// Cấu hình API Skypec
const HOST = 'https://elearning.skypec.com.vn';
const API_PATHS = {
  token: '/skypec2.authentication.api/connect/token',
  profile: '/skypec2.authentication.api/api/v1/HrProfile/GetByUsername',
  searchClass: '/skypec2.lms.api/api/v1/LmsHistory/SearchClass',
  joinClass: '/skypec2.lms.api/api/v1/LmsClass/FrUserJoinClassNew',
  heartbeat: '/skypec2.lms.api/api/v1/LmsClassContent/frUserUpdateViewNew'
};

// Trạng thái ứng dụng
const state = {
  token: null,
  user: null,
  classes: [],
  selectedClass: null,
  activeIntervalId: null,
  sessionStartTime: null,
  timerIntervalId: null,
  estimatedMinutes: 0,
  logs: []
};

// Khởi chạy khi tài liệu sẵn sàng
document.addEventListener('DOMContentLoaded', () => {
  initApp();
});

// Khởi tạo ứng dụng
function initApp() {
  setupEventListeners();
  checkSavedCredentials();
}

// Cài đặt các sự kiện tương tác
function setupEventListeners() {
  // Đăng nhập
  document.getElementById('login-form').addEventListener('submit', handleLogin);
  
  // Đăng xuất
  document.getElementById('btn-logout').addEventListener('click', handleLogout);
  
  // Thay đổi năm học
  document.getElementById('year-select').addEventListener('change', () => {
    loadClasses(document.getElementById('year-select').value);
  });
  
  // Làm mới danh sách
  document.getElementById('btn-refresh').addEventListener('click', () => {
    loadClasses(document.getElementById('year-select').value);
  });
  
  // Quay lại danh sách lớp học
  document.getElementById('btn-back-to-list').addEventListener('click', () => {
    if (state.activeIntervalId) {
      if (!confirm('Tiến trình duy trì thời gian đang chạy. Quay lại danh sách sẽ không dừng tiến trình, nhưng Khầy nên giữ màn hình này để theo dõi. Khầy có muốn tiếp tục?')) {
        return;
      }
    }
    showScreen('main-screen');
  });
  
  // Nút bắt đầu duy trì ngầm
  document.getElementById('btn-start-reading').addEventListener('click', startReading);
  
  // Nút dừng duy trì ngầm
  document.getElementById('btn-stop-reading').addEventListener('click', stopReading);

  // Thay đổi số phút mục tiêu thủ công
  document.getElementById('txt-target-time').addEventListener('input', updateProgressUI);
}

// Hàm gửi request hỗ trợ Bypass CORS (nếu chạy trong ứng dụng Android WebView)
async function requestApi(url, options = {}) {
  // Nếu đang chạy trong Android WebView có hỗ trợ hàm gọi native bypass CORS
  if (window.Android && window.Android.makeRequest) {
    return new Promise((resolve, reject) => {
      const callbackName = 'api_callback_' + Math.random().toString(36).substring(2, 11);
      window[callbackName] = (responseStr) => {
        delete window[callbackName];
        try {
          const res = JSON.parse(responseStr);
          if (res.error) {
            reject(new Error(res.error));
          } else {
            resolve(res);
          }
        } catch (e) {
          reject(e);
        }
      };
      
      const nativeOptions = {
        url: url.startsWith('http') ? url : HOST + url,
        method: options.method || 'GET',
        headers: options.headers || {},
        body: options.body || null,
        callback: callbackName
      };
      
      window.Android.makeRequest(JSON.stringify(nativeOptions));
    });
  }
  
  // Nếu chạy trên trình duyệt thường (cần tắt CORS trên Chrome mới chạy được trên PC)
  const fetchUrl = url.startsWith('http') ? url : HOST + url;
  const response = await fetch(fetchUrl, options);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Lỗi HTTP ${response.status}`);
  }
  return response.json();
}

// Kiểm tra thông tin đăng nhập đã lưu
function checkSavedCredentials() {
  const savedUser = localStorage.getItem('skypec_username');
  const savedPass = localStorage.getItem('skypec_password');
  
  if (savedUser && savedPass) {
    document.getElementById('username').value = savedUser;
    document.getElementById('password').value = savedPass;
    // Tự động đăng nhập
    performLogin(savedUser, savedPass);
  }
}

// Xử lý đăng nhập
async function handleLogin(e) {
  e.preventDefault();
  const user = document.getElementById('username').value.trim();
  const pass = document.getElementById('password').value.trim();
  
  performLogin(user, pass);
}

// Thực hiện đăng nhập
async function performLogin(username, password) {
  const errorEl = document.getElementById('login-error');
  errorEl.classList.add('hidden');
  
  // Hiển thị trạng thái đang đăng nhập
  const btn = document.querySelector('#login-form button');
  const originalText = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Đang đăng nhập...';
  
  try {
    // 1. Lấy Access Token
    const tokenData = await requestApi(API_PATHS.token, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: `grant_type=password&client_id=web&username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}&scope=`
    });
    
    state.token = tokenData.access_token;
    
    // Lưu thông tin đăng nhập nếu thành công
    localStorage.setItem('skypec_username', username);
    localStorage.setItem('skypec_password', password);
    
    // 2. Lấy thông tin cá nhân học viên
    try {
      const profileData = await requestApi(`${API_PATHS.profile}?username=${encodeURIComponent(username)}`, {
        headers: { 'Authorization': `Bearer ${state.token}` }
      });
      state.user = profileData.data;
    } catch (e) {
      // Fallback nếu lỗi profile
      state.user = { displayName: username, departmentName: 'Skypec' };
    }
    
    // Hiển thị thông tin học viên lên giao diện
    document.getElementById('user-display-name').textContent = state.user.displayName || username;
    document.getElementById('user-dept').textContent = state.user.departmentName || 'Học viên Skypec';
    
    showScreen('main-screen');
    loadClasses(document.getElementById('year-select').value);
    
  } catch (err) {
    console.error(err);
    errorEl.textContent = 'Đăng nhập thất bại. Vui lòng kiểm tra lại tài khoản hoặc mật khẩu.';
    errorEl.classList.remove('hidden');
  } finally {
    btn.disabled = false;
    btn.innerHTML = originalText;
  }
}

// Xử lý đăng xuất
function handleLogout() {
  if (state.activeIntervalId) {
    if (!confirm('Khầy có muốn dừng việc duy trì tiến độ đọc sách hiện tại và đăng xuất không?')) {
      return;
    }
    stopReading();
  }
  
  state.token = null;
  state.user = null;
  state.classes = [];
  localStorage.removeItem('skypec_username');
  localStorage.removeItem('skypec_password');
  
  document.getElementById('password').value = '';
  showScreen('login-screen');
}

// Tải danh sách lớp học theo năm
async function loadClasses(year) {
  const container = document.getElementById('class-list');
  container.innerHTML = `
    <div class="loading-spinner">
      <i class="fa-solid fa-circle-notch fa-spin"></i>
      <p>Đang tải danh sách lớp học năm ${year}...</p>
    </div>
  `;
  
  try {
    const res = await requestApi(API_PATHS.searchClass, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${state.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        year: parseInt(year),
        month: 0,
        key: '',
        status: -1,
        isFinish: -1,
        pageIndex: 1,
        pageSize: 100,
        orderId: 0
      })
    });
    
    if (res.status && res.data && res.data.details) {
      state.classes = res.data.details;
      renderClasses(state.classes);
    } else {
      container.innerHTML = `<div class="error-msg">Không lấy được danh sách lớp học. Lỗi: ${res.message || 'Unknown'}</div>`;
    }
  } catch (err) {
    console.error(err);
    container.innerHTML = `<div class="error-msg">Không thể kết nối đến máy chủ Skypec. Vui lòng thử lại.</div>`;
  }
}

// Vẽ danh sách lớp học lên giao diện
function renderClasses(classes) {
  const container = document.getElementById('class-list');
  if (classes.length === 0) {
    container.innerHTML = `<div class="loading-spinner"><p>Không có lớp học nào trong năm này.</p></div>`;
    return;
  }
  
  container.innerHTML = '';
  classes.forEach(c => {
    const card = document.createElement('div');
    const isCompleted = c.tenTrangThai === 'Hoàn thành';
    card.className = `class-card ${isCompleted ? 'completed' : ''}`;
    
    // Tìm kiếm thời gian yêu cầu tối thiểu
    const timeReq = c.planType === '1' ? '430 phút' : 'Yêu cầu';
    
    card.innerHTML = `
      <div class="class-icon">
        <i class="fa-solid ${isCompleted ? 'fa-circle-check' : 'fa-book'}"></i>
      </div>
      <div class="class-details">
        <h4>${c.classTitle}</h4>
        <div class="class-meta">
          <span>${timeReq}</span>
          <span class="class-status ${isCompleted ? 'completed' : 'active'}">${c.tenTrangThai}</span>
        </div>
      </div>
    `;
    
    card.addEventListener('click', () => openClassDetails(c));
    container.appendChild(card);
  });
}

// Mở màn hình chi tiết lớp học
async function openClassDetails(classInfo) {
  state.selectedClass = classInfo;
  
  // Đặt thông tin cơ bản trước
  document.getElementById('reader-class-title').textContent = classInfo.classTitle;
  document.getElementById('reader-class-code').textContent = `Mã lớp: ${classInfo.code || 'N/A'}`;
  document.getElementById('progress-text').textContent = 'Đang tải tiến độ...';
  document.getElementById('progress-bar-fill').style.width = '0%';
  
  // Ẩn bảng đọc chạy ngầm nếu trước đó đang hiển thị
  document.getElementById('active-reading-section').classList.add('hidden');
  document.getElementById('btn-start-reading').classList.remove('hidden');
  document.getElementById('btn-stop-reading').classList.add('hidden');
  
  showScreen('reader-screen');
  
  try {
    // 1. Gọi API lấy thông tin chi tiết lớp học để biết số phút yêu cầu tối thiểu (minTimeRequired)
    let minTimeRequired = 430; // Mặc định là 430
    try {
      const classDetail = await requestApi(`/LmsClass/${classInfo.id}`, {
        headers: { 'Authorization': `Bearer ${state.token}` }
      });
      if (classDetail.status && classDetail.data && classDetail.data.minTimeRequired) {
        minTimeRequired = classDetail.data.minTimeRequired;
      }
    } catch (e) {
      console.log('Không lấy được minTimeRequired, sử dụng mặc định 430:', e);
    }
    
    document.getElementById('txt-target-time').value = minTimeRequired;

    // 2. Gọi API FrUserJoinClassNew để lấy classUserId thực tế của học viên trong lớp này
    const joinData = await requestApi(`${API_PATHS.joinClass}/${classInfo.id}`, {
      headers: { 'Authorization': `Bearer ${state.token}` }
    });
    
    if (joinData.status && joinData.data) {
      // Lưu lại thông tin chi tiết bao gồm classUserId và danh sách học tập của lớp
      state.selectedClass.classUserId = joinData.data.id;
      
      // Lấy ra tiến độ của sách (thường có classContentId)
      const learningHistories = joinData.data.lmsClassUserLearning || [];
      if (learningHistories.length > 0) {
        const bookProgress = learningHistories[0]; // Cuốn sách đầu tiên trong lớp
        state.selectedClass.currentLearnTime = bookProgress.learnTime || 0;
        state.selectedClass.isFinish = bookProgress.isFinish;
      } else {
        state.selectedClass.currentLearnTime = 0;
        state.selectedClass.isFinish = false;
      }
      
      updateProgressUI();
    } else {
      alert('Không thể lấy chi tiết lớp học từ hệ thống.');
    }
  } catch (err) {
    console.error(err);
    alert('Lỗi kết nối khi tải chi tiết lớp học.');
  }
}

// Cập nhật giao diện tiến độ đọc sách
function updateProgressUI() {
  const current = state.selectedClass.currentLearnTime || 0;
  const target = parseInt(document.getElementById('txt-target-time').value) || 430;
  const percent = Math.min(100, (current / target) * 100);
  
  document.getElementById('progress-text').textContent = `${current.toFixed(1)} / ${target} phút (${percent.toFixed(1)}%)`;
  document.getElementById('progress-bar-fill').style.width = `${percent}%`;
  
  // Nếu đã đạt mục tiêu thì đổi màu tiến trình sang màu xanh lá
  if (current >= target) {
    document.getElementById('progress-bar-fill').style.background = 'linear-gradient(135deg, #38ef7d 0%, #11998e 100%)';
    document.getElementById('progress-bar-fill').style.boxShadow = '0 0 8px rgba(56, 239, 125, 0.5)';
  } else {
    document.getElementById('progress-bar-fill').style.background = 'var(--primary-gradient)';
    document.getElementById('progress-bar-fill').style.boxShadow = '0 0 8px var(--primary-color)';
  }
}

// Bắt đầu chế độ duy trì thời gian chạy ngầm
function startReading() {
  const classUserId = state.selectedClass.classUserId;
  if (!classUserId) {
    alert('Không tìm thấy mã học viên (classUserId). Vui lòng tải lại trang.');
    return;
  }
  
  state.sessionStartTime = new Date();
  state.estimatedMinutes = 0;
  state.logs = [];
  
  // Dọn dẹp nhật ký cũ
  const logsContainer = document.getElementById('console-logs');
  logsContainer.innerHTML = '';
  
  // Cập nhật giao diện nút
  document.getElementById('btn-start-reading').classList.add('hidden');
  document.getElementById('btn-stop-reading').classList.remove('hidden');
  document.getElementById('active-reading-section').classList.remove('hidden');
  
  // Ghi log bắt đầu
  addLog('Bắt đầu tiến trình duy trì thời gian đọc sách...', 'info');
  addLog(`Mã học viên: ${classUserId}`, 'info');
  
  // 1. Kích hoạt phát nhạc câm nếu chạy trên trình duyệt để chống sleep tab
  const audio = document.getElementById('silent-audio');
  audio.play().catch(e => console.log('Cần tương tác người dùng để phát audio: ', e));
  
  // 2. Gửi tín hiệu nhịp tim đầu tiên ngay lập tức
  sendHeartbeat();
  
  // 3. Thiết lập vòng lặp nhịp tim mỗi 60 giây (60000 ms)
  state.activeIntervalId = setInterval(sendHeartbeat, 60000);
  
  // 4. Thiết lập bộ đếm thời gian hiển thị giao diện mỗi giây
  state.timerIntervalId = setInterval(updateSessionTimer, 1000);
  
  // 5. Nếu chạy trên app Android Native, thông báo cho Native biết để bật Foreground Service chạy ngầm vĩnh viễn
  if (window.Android && window.Android.startForegroundService) {
    window.Android.startForegroundService(
      state.selectedClass.classTitle,
      classUserId,
      state.token
    );
    addLog('[Android] Đã kích hoạt Foreground Service chạy ngầm hệ thống.', 'success');
  }
}

// Dừng chế độ duy trì thời gian
function stopReading() {
  if (state.activeIntervalId) {
    clearInterval(state.activeIntervalId);
    state.activeIntervalId = null;
  }
  if (state.timerIntervalId) {
    clearInterval(state.timerIntervalId);
    state.timerIntervalId = null;
  }
  
  // Dừng nhạc câm
  const audio = document.getElementById('silent-audio');
  audio.pause();
  
  // Cập nhật giao diện nút
  document.getElementById('btn-start-reading').classList.remove('hidden');
  document.getElementById('btn-stop-reading').classList.add('hidden');
  
  addLog('Đã dừng tiến trình duy trì thời gian đọc sách.', 'info');
  
  // Thông báo dừng dịch vụ chạy ngầm của Android Native
  if (window.Android && window.Android.stopForegroundService) {
    window.Android.stopForegroundService();
    addLog('[Android] Đã dừng Foreground Service chạy ngầm.', 'info');
  }
  
  // Tự động tải lại thông tin lớp học để cập nhật thời gian thực tế vừa tăng
  openClassDetails(state.selectedClass);
}

// Gửi tín hiệu nhịp tim (Heartbeat) đến máy chủ Skypec
async function sendHeartbeat() {
  const classUserId = state.selectedClass.classUserId;
  const now = new Date();
  const timeString = now.toTimeString().split(' ')[0];
  
  try {
    // Gửi yêu cầu cập nhật tiến độ xem sách
    const res = await requestApi(`${API_PATHS.heartbeat}/${classUserId}`, {
      headers: {
        'Authorization': `Bearer ${state.token}`
      }
    });
    
    if (res.status) {
      addLog(`[${timeString}] Heartbeat gửi thành công.`, 'success');
      
      // Cập nhật số phút tăng ước tính (+1 phút mỗi lần gửi thành công)
      if (state.activeIntervalId) { // Bỏ qua lần gửi đầu tiên ngay lập tức
        state.estimatedMinutes += 1.0;
        document.getElementById('estimated-increase').textContent = `+${state.estimatedMinutes.toFixed(1)} phút`;
        
        // Tăng tạm thời trên giao diện cho Khầy thấy tiến độ thay đổi trực quan
        state.selectedClass.currentLearnTime += 1.0;
        updateProgressUI();
        
        // Kiểm tra tự động dừng khi đạt mục tiêu
        const target = parseInt(document.getElementById('txt-target-time').value) || 430;
        const isAutoStop = document.getElementById('chk-auto-stop').checked;
        if (isAutoStop && state.selectedClass.currentLearnTime >= target) {
          addLog(`Đạt tiến độ yêu cầu ${target} phút! Tự động dừng...`, 'success');
          // Phát chuông báo hoàn thành bằng âm thanh web
          playAlertSound();
          
          // Gửi thông báo đẩy Native Android (Rung + Âm thanh hệ thống) nếu có
          if (window.Android && window.Android.showCompletionNotification) {
            window.Android.showCompletionNotification(
              "Hoàn thành mục tiêu đọc sách! 🎉",
              `Lớp "${state.selectedClass.classTitle}" đã đạt đủ ${target} phút.`
            );
          }
          
          stopReading();
        }
      }
    } else {
      addLog(`[${timeString}] Lỗi phản hồi từ máy chủ: ${res.message || 'Không rõ'}`, 'error');
    }
  } catch (err) {
    console.error(err);
    addLog(`[${timeString}] Gửi Heartbeat thất bại (Lỗi kết nối mạng).`, 'error');
  }
}

// Cập nhật bộ đếm thời gian phiên làm việc
function updateSessionTimer() {
  const diffMs = new Date() - state.sessionStartTime;
  const diffSec = Math.floor(diffMs / 1000);
  
  const hours = Math.floor(diffSec / 3600).toString().padStart(2, '0');
  const minutes = Math.floor((diffSec % 3600) / 60).toString().padStart(2, '0');
  const seconds = (diffSec % 60).toString().padStart(2, '0');
  
  document.getElementById('session-timer').textContent = `${hours}:${minutes}:${seconds}`;
}

// Thêm dòng log vào ô console nhật ký
function addLog(message, type = 'info') {
  const logsContainer = document.getElementById('console-logs');
  const logItem = document.createElement('div');
  logItem.className = `log-item ${type}`;
  logItem.textContent = message;
  logsContainer.appendChild(logItem);
  
  // Tự động cuộn xuống dưới cùng
  logsContainer.scrollTop = logsContainer.scrollHeight;
}

// Phát âm thanh báo khi hoàn thành
function playAlertSound() {
  try {
    const context = new (window.AudioContext || window.webkitAudioContext)();
    const osc = context.createOscillator();
    const gain = context.createGain();
    
    osc.type = 'sine';
    osc.frequency.setValueAtTime(880, context.currentTime); // Nốt A5
    osc.frequency.setValueAtTime(1200, context.currentTime + 0.15);
    
    gain.gain.setValueAtTime(0.5, context.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.01, context.currentTime + 0.5);
    
    osc.connect(gain);
    gain.connect(context.destination);
    
    osc.start();
    osc.stop(context.currentTime + 0.5);
  } catch (e) {
    console.log('Không thể phát âm thanh cảnh báo: ', e);
  }
}

// Chuyển đổi giữa các màn hình ứng dụng
function showScreen(screenId) {
  document.querySelectorAll('.screen').forEach(screen => {
    screen.classList.remove('active');
  });
  document.getElementById(screenId).classList.add('active');
}
