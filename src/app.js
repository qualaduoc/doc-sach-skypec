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
  filteredClasses: [], // Danh sách sau khi lọc để click chính xác
  selectedClass: null,
  activeIntervalId: null,
  sessionStartTime: null,
  timerIntervalId: null,
  estimatedMinutes: 0,
  logs: [],
  ws: null,              // Quản lý kết nối WebSocket LRS
  wsPingInterval: null   // Vòng lặp gửi gói tin Ping duy trì socket
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
  
  // Thay đổi bộ lọc hiển thị
  document.getElementById('year-select').addEventListener('change', () => {
    loadClasses(document.getElementById('year-select').value);
  });
  
  document.getElementById('status-select').addEventListener('change', filterAndRenderClasses);
  document.getElementById('class-search-input').addEventListener('input', filterAndRenderClasses);
  
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

  // Nút sao chép nhật ký
  document.getElementById('btn-copy-log').addEventListener('click', copyLogsToClipboard);
}

// Hàm gửi request hỗ trợ Bypass CORS (nếu chạy trong ứng dụng Android WebView)
async function requestApi(url, options = {}) {
  const method = options.method || 'GET';
  
  // Thiết lập các header mặc định giống như trình duyệt/python script để tránh lỗi lọc của server
  const headers = {
    'Accept': 'application/json, text/plain, */*',
    ...options.headers
  };
  
  // Tự động bổ sung Content-Type nếu gửi dữ liệu POST
  if (method === 'POST' && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  // BỎ QUA NATIVE BRIDGE, DÙNG FETCH TRỰC TIẾP TRÊN WEBVIEW!
  // Vì WebView đã được bật setAllowUniversalAccessFromFileURLs(true) nên gọi fetch trực tiếp không bị chặn CORS
  const fetchUrl = url.startsWith('http') ? url : HOST + url;
  const fetchOptions = {
    method: method,
    headers: headers
  };
  
  if (options.body) {
    fetchOptions.body = options.body;
  }
  
  try {
    const response = await fetch(fetchUrl, fetchOptions);
    
    if (!response.ok) {
      let errText = "";
      try {
        errText = await response.text();
      } catch(e) {}
      
      // Đóng gói thông tin lỗi chi tiết dạng JSON để hiển thị chẩn đoán
      const errObj = {
        error: `Lỗi HTTP ${response.status}`,
        statusCode: response.status,
        url: fetchUrl,
        method: method,
        requestHeaders: {
          ...headers,
          'Authorization': headers['Authorization'] ? (headers['Authorization'].substring(0, 25) + '...') : 'RỖNG'
        },
        serverDetail: errText
      };
      throw new Error(JSON.stringify(errObj));
    }
    
    return await response.json();
  } catch (e) {
    if (e.message && e.message.startsWith('{')) {
      throw e;
    }
    // Lỗi kết nối mạng (Network Error)
    const errObj = {
      error: e.message || "Lỗi kết nối mạng",
      statusCode: 0,
      url: fetchUrl,
      method: method
    };
    throw new Error(JSON.stringify(errObj));
  }
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
    
    // Cố gắng lấy họ tên đầy đủ từ các trường khác nhau nếu displayName chỉ là mã nhân viên
    let displayName = state.user.fullName || state.user.employeeName || state.user.hoTen || state.user.displayName || username;
    if (displayName === username && state.user.lastName && state.user.firstName) {
      displayName = `${state.user.lastName} ${state.user.firstName}`;
    }
    
    document.getElementById('user-display-name').textContent = displayName;
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

// Định dạng ngày tháng DD/MM/YYYY từ chuỗi ISO
function formatDate(dateStr) {
  if (!dateStr) return '';
  try {
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return '';
    const day = d.getDate().toString().padStart(2, '0');
    const month = (d.getMonth() + 1).toString().padStart(2, '0');
    const year = d.getFullYear();
    return `${day}/${month}/${year}`;
  } catch (e) {
    return '';
  }
}

// Tải danh sách lớp học (hỗ trợ gom tất cả các năm song song)
async function loadClasses(year) {
  const tbody = document.getElementById('dashboard-table-body');
  tbody.innerHTML = `
    <tr>
      <td colspan="5" style="text-align: center; padding: 35px;">
        <div class="loading-spinner">
          <i class="fa-solid fa-circle-notch fa-spin"></i>
          <p style="margin-top: 8px;">Đang tải dữ liệu học tập...</p>
        </div>
      </td>
    </tr>
  `;

  try {
    let yearsToFetch = [];
    if (year === 'all') {
      yearsToFetch = [2022, 2023, 2024, 2025, 2026];
    } else {
      yearsToFetch = [parseInt(year)];
    }

    // Gọi API song song cho tất cả các năm cần lấy
    const promises = yearsToFetch.map(y => requestApi(API_PATHS.searchClass, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${state.token}`
      },
      body: JSON.stringify({
        year: y,
        month: 0,
        key: '',
        status: -1,
        isFinish: -1,
        pageIndex: 1,
        pageSize: 200, // Tải tối đa 200 lớp mỗi năm để hiển thị hết các trang đằng sau
        orderId: 0
      })
    }));

    const results = await Promise.all(promises);
    let mergedClasses = [];
    const seenIds = new Set();

    results.forEach((res, idx) => {
      if (res.status && res.data && res.data.details) {
        const yearVal = yearsToFetch[idx];
        res.data.details.forEach(item => {
          const classId = item.id || item.classId;
          if (classId && !seenIds.has(classId)) {
            seenIds.add(classId);
            item.year_group = yearVal;
            mergedClasses.push(item);
          }
        });
      }
    });

    // Sắp xếp theo ngày bắt đầu lớp học giảm dần (mới nhất lên đầu)
    mergedClasses.sort((a, b) => {
      const dateA = new Date(a.classStartDate || a.createdDate || 0);
      const dateB = new Date(b.classStartDate || b.createdDate || 0);
      return dateB - dateA;
    });

    state.classes = mergedClasses;
    
    // Tính toán và hiển thị chỉ số KPI tổng thể (trên tất cả các năm đã tải)
    updateKPICards(state.classes);
    
    // Tiến hành lọc và vẽ giao diện
    filterAndRenderClasses();
    
  } catch (err) {
    console.error(err);
    tbody.innerHTML = `
      <tr>
        <td colspan="5" style="text-align: center; padding: 30px; color: var(--danger-color);">
          Không thể tải dữ liệu. Vui lòng nhấn nút "Làm mới" để thử lại.
        </td>
      </tr>
    `;
  }
}

// Cập nhật các thẻ KPI học tập
function updateKPICards(classList) {
  const totalClasses = classList.length;
  const studyingClasses = classList.filter(c => c.tenTrangThai === 'Đang học').length;
  const totalPoints = classList.reduce((sum, c) => sum + (c.rewardPoints || 0), 0);

  document.getElementById('kpi-total-classes').textContent = totalClasses;
  document.getElementById('kpi-studying-classes').textContent = studyingClasses;
  document.getElementById('kpi-total-points').textContent = totalPoints;
}

// Lọc danh sách lớp học theo từ khóa, năm và trạng thái
function filterAndRenderClasses() {
  const searchQuery = document.getElementById('class-search-input').value.toLowerCase().trim();
  const selectedYear = document.getElementById('year-select').value;
  const selectedStatus = document.getElementById('status-select').value;

  let filtered = state.classes;

  // 1. Lọc theo năm
  if (selectedYear !== 'all') {
    const yInt = parseInt(selectedYear);
    filtered = filtered.filter(c => c.year_group === yInt);
  }

  // 2. Lọc theo trạng thái
  if (selectedStatus === 'studying') {
    filtered = filtered.filter(c => c.tenTrangThai === 'Đang học');
  } else if (selectedStatus === 'finished') {
    filtered = filtered.filter(c => c.tenTrangThai === 'Hoàn thành');
  }

  // 3. Lọc theo từ khóa tìm kiếm
  if (searchQuery) {
    filtered = filtered.filter(c => {
      const title = (c.classTitle || '').toLowerCase();
      const code = (c.code || '').toLowerCase();
      return title.includes(searchQuery) || code.includes(searchQuery);
    });
  }

  state.filteredClasses = filtered;
  renderClassesTable(state.filteredClasses);
}

// Vẽ bảng danh sách lớp học lên giao diện
function renderClassesTable(classesList) {
  const tbody = document.getElementById('dashboard-table-body');
  
  if (classesList.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="5" style="text-align: center; padding: 30px; color: var(--text-muted);">
          Không tìm thấy lớp học nào khớp với bộ lọc.
        </td>
      </tr>
    `;
    return;
  }

  tbody.innerHTML = classesList.map((c, index) => {
    const title = c.classTitle || 'N/A';
    const fromDate = formatDate(c.classStartDate);
    const toDate = formatDate(c.classEndDate) || 'Không giới hạn';
    const points = c.rewardPoints || 0;
    const isCompleted = c.tenTrangThai === 'Hoàn thành';
    const statusClass = isCompleted ? 'finished' : 'studying';
    const statusText = c.tenTrangThai || 'Đang học';

    return `
      <tr>
        <td class="class-title-cell" data-label="Lớp học">
          ${title}
          <div style="margin-top: 4px;">
            <span class="status-tag ${statusClass}">${statusText}</span>
          </div>
        </td>
        <td data-label="Từ ngày" class="date-text" style="text-align: center;">${fromDate}</td>
        <td data-label="Đến ngày" class="date-text" style="text-align: center;">${toDate}</td>
        <td data-label="Điểm" style="text-align: center;">
          <span class="points-badge">${points}</span>
        </td>
        <td data-label="Thao tác" style="text-align: center;">
          <button class="btn-table-action" onclick="handleClassClick(${index})">
            <i class="fa-solid fa-arrow-right-to-bracket"></i> Vào học
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

// Xử lý khi click chọn lớp học từ bảng dể vào học
function handleClassClick(index) {
  const classInfo = state.filteredClasses[index];
  if (classInfo) {
    openClassDetails(classInfo);
  }
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
  
  // Ghi nhận nhật ký để gỡ lỗi trực quan trên giao diện
  document.getElementById('console-logs').innerHTML = ''; // Reset logs cũ
  addLog('Đang tải chi tiết lớp học...', 'info');
  addLog('Dữ liệu nhận được: ' + JSON.stringify(classInfo), 'info');
  
  // Kiểm tra tính hợp lệ của Token
  const tokenSnippet = state.token ? (state.token.substring(0, 10) + '...' + state.token.substring(state.token.length - 10)) : 'RỖNG';
  addLog('Kiểm tra Token gửi đi: ' + tokenSnippet + ' (Độ dài: ' + (state.token ? state.token.length : 0) + ')', 'info');
  
  try {
    // Thử tất cả các khoá ID có thể có từ API danh sách của Skypec
    const classId = classInfo.id || classInfo.classId || classInfo.lmsClassId || classInfo.classUserId;
    addLog('Sử dụng Class ID: ' + classId, 'info');
    
    if (!classId) {
      throw new Error('Không tìm thấy ID lớp học trong dữ liệu hệ thống.');
    }
    
    // 1. Gọi API lấy thông tin chi tiết lớp học để biết số phút yêu cầu tối thiểu (minTimeRequired)
    let minTimeRequired = 430; // Mặc định là 430
    addLog('Đang tải cấu hình thời gian yêu cầu...', 'info');
    try {
      const classDetail = await requestApi(`/skypec2.lms.api/api/v1/LmsClass/${classId}`, {
        headers: { 'Authorization': `Bearer ${state.token}` }
      });
      if (classDetail.status && classDetail.data && classDetail.data.minTimeRequired) {
        minTimeRequired = classDetail.data.minTimeRequired;
        addLog('Thời gian yêu cầu tối thiểu: ' + minTimeRequired + ' phút', 'success');
      } else {
        addLog('Không tìm thấy minTimeRequired trong phản hồi, dùng mặc định 430 phút', 'info');
      }
    } catch (e) {
      addLog('Không lấy được minTimeRequired (dùng mặc định 430): ' + e.message, 'info');
    }
    
    document.getElementById('txt-target-time').value = minTimeRequired;

    // 2. Gọi API FrUserJoinClassNew để lấy classUserId thực tế của học viên trong lớp này
    addLog('Đang tải tiến độ học tập thực tế...', 'info');
    let joinData = null;
    
    try {
      addLog('Thử tải tiến độ qua Path: ' + `${API_PATHS.joinClass}/${classId}`, 'info');
      joinData = await requestApi(`${API_PATHS.joinClass}/${classId}`, {
        headers: { 'Authorization': `Bearer ${state.token}` }
      });
    } catch (err1) {
      const is404 = err1.message && (err1.message.includes('404') || err1.message.includes('Not Found'));
      if (is404) {
        addLog('Đường dẫn Path trả về 404. Tự động chuyển sang thử nghiệm dạng Query (?classId=)...', 'info');
        try {
          joinData = await requestApi(`${API_PATHS.joinClass}?classId=${classId}`, {
            headers: { 'Authorization': `Bearer ${state.token}` }
          });
        } catch (err2) {
          const is404Query = err2.message && (err2.message.includes('404') || err2.message.includes('Not Found'));
          if (is404Query) {
            addLog('Đường dẫn Query (?classId=) trả về 404. Thử tiếp với Query (?id=)...', 'info');
            joinData = await requestApi(`${API_PATHS.joinClass}?id=${classId}`, {
              headers: { 'Authorization': `Bearer ${state.token}` }
            });
          } else {
            throw err2;
          }
        }
      } else {
        throw err1;
      }
    }
    
    if (joinData && joinData.status && joinData.data) {
      // Lưu lại thông tin chi tiết bao gồm classUserId và danh sách học tập của lớp
      state.selectedClass.classUserId = joinData.data.id;
      addLog('Lấy mã học viên thành công (classUserId): ' + joinData.data.id, 'success');
      
      // Lấy ra tiến độ của sách (thường có classContentId)
      const learningHistories = joinData.data.lmsClassUserLearning || [];
      state.selectedClass.isFinish = joinData.data.isFinish === 1 || joinData.data.isFinish === true;
      
      if (learningHistories.length > 0) {
        const bookProgress = learningHistories[0]; // Cuốn sách đầu tiên trong lớp
        state.selectedClass.currentLearnTime = bookProgress.learnTime || joinData.data.totalTime || 0;
        state.selectedClass.learningId = bookProgress.id; // LƯU LẠI learningId để kết nối WebSocket LRS
        if (bookProgress.isFinish !== undefined && bookProgress.isFinish !== null) {
          state.selectedClass.isFinish = bookProgress.isFinish;
        }
      } else {
        state.selectedClass.currentLearnTime = joinData.data.totalTime || joinData.data.learnTime || 0;
        state.selectedClass.learningId = null;
      }
      
      addLog('Mã LRS Learning ID: ' + (state.selectedClass.learningId || 'KHÔNG CÓ'), 'success');
      addLog('Thời gian đã học: ' + state.selectedClass.currentLearnTime.toFixed(1) + ' phút', 'success');
      addLog('Trạng thái hoàn thành: ' + (state.selectedClass.isFinish ? 'Đã hoàn thành' : 'Chưa hoàn thành'), 'info');
      
      updateProgressUI();
    } else {
      throw new Error('Không thể lấy chi tiết tiến độ lớp học từ hệ thống Skypec.');
    }
  } catch (err) {
    addLog('Lỗi tải chi tiết lớp học: ' + err.message, 'error');
    console.error(err);
    alert('Lỗi kết nối khi tải chi tiết lớp học: ' + (err.message || err));
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
  
  // 2. Kích hoạt kết nối WebSocket LRS để máy chủ bắt đầu tính toán thời gian học thực tế
  if (state.selectedClass.learningId) {
    startWebSocket(state.selectedClass.learningId);
  } else {
    addLog('Cảnh báo: Không tìm thấy learningId, thời gian học có thể không được cộng trên server!', 'error');
  }
  
  // 3. Gửi tín hiệu nhịp tim đầu tiên ngay lập tức
  sendHeartbeat();
  
  // 4. Thiết lập vòng lặp nhịp tim mỗi 60 giây (60000 ms)
  state.activeIntervalId = setInterval(sendHeartbeat, 60000);
  
  // 5. Thiết lập bộ đếm thời gian hiển thị giao diện mỗi giây
  state.timerIntervalId = setInterval(updateSessionTimer, 1000);
  
  // 6. Nếu chạy trên app Android Native, thông báo cho Native biết để bật Foreground Service chạy ngầm vĩnh viễn
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
  
  // Dừng kết nối WebSocket LRS
  stopWebSocket();
  
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
  
  // Tự động tải lại tiến độ học tập mới từ server (không làm sạch nhật ký)
  refreshClassProgress();
}

// Tải lại tiến độ học tập mới từ server mà không xóa nhật ký hoạt động
async function refreshClassProgress() {
  try {
    const classId = state.selectedClass.classId || state.selectedClass.id;
    addLog('Đang cập nhật tiến độ học tập mới nhất từ máy chủ...', 'info');
    
    const joinData = await requestApi(`${API_PATHS.joinClass}/${classId}`, {
      headers: { 'Authorization': `Bearer ${state.token}` }
    });
    
    if (joinData && joinData.status && joinData.data) {
      const learningHistories = joinData.data.lmsClassUserLearning || [];
      if (learningHistories.length > 0) {
        state.selectedClass.currentLearnTime = learningHistories[0].learnTime || joinData.data.totalTime || 0;
        state.selectedClass.isFinish = learningHistories[0].isFinish;
      } else {
        state.selectedClass.currentLearnTime = joinData.data.totalTime || joinData.data.learnTime || 0;
      }
      
      addLog(`[Cập nhật thành công] Tiến độ thực tế trên server: ${state.selectedClass.currentLearnTime.toFixed(1)} phút`, 'success');
      updateProgressUI();
    }
  } catch (e) {
    addLog('Không thể cập nhật tiến độ mới: ' + e.message, 'error');
  }
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

// Sao chép toàn bộ nhật ký hoạt động vào bộ nhớ tạm
function copyLogsToClipboard() {
  const logsContainer = document.getElementById('console-logs');
  const text = logsContainer.innerText;
  
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      alert('Đã sao chép nhật ký vào bộ nhớ tạm!');
    }).catch(err => {
      fallbackCopyText(text);
    });
  } else {
    fallbackCopyText(text);
  }
}

function fallbackCopyText(text) {
  const textArea = document.createElement("textarea");
  textArea.value = text;
  textArea.style.position = "fixed";
  document.body.appendChild(textArea);
  textArea.focus();
  textArea.select();
  try {
    const successful = document.execCommand('copy');
    if (successful) {
      alert('Đã sao chép nhật ký vào bộ nhớ tạm!');
    } else {
      alert('Không thể sao chép nhật ký.');
    }
  } catch (err) {
    alert('Lỗi khi sao chép: ' + err);
  }
  document.body.removeChild(textArea);
}

// Bắt đầu kết nối WebSocket LRS để máy chủ tính thời gian học
function startWebSocket(learningId) {
  stopWebSocket(); // Đảm bảo không có kết nối thừa trước đó
  
  const wsUrl = `wss://elearning.skypec.com.vn/skypec2.lms.api/socket?learningId=${learningId}&access_token=${state.token}`;
  addLog('Đang kết nối WebSocket LRS...', 'info');
  
  try {
    state.ws = new WebSocket(wsUrl);
    
    state.ws.onopen = () => {
      addLog('Kết nối WebSocket LRS thành công. Đang gửi Handshake...', 'success');
      
      // Gửi handshake của SignalR (kết thúc bằng ký tự 0x1e - ASCII 30)
      const handshake = JSON.stringify({ protocol: "json", version: 1 }) + String.fromCharCode(30);
      state.ws.send(handshake);
      
      // Gửi ping mỗi 15 giây để duy trì kết nối hoạt động
      state.wsPingInterval = setInterval(() => {
        if (state.ws && state.ws.readyState === WebSocket.OPEN) {
          state.ws.send(JSON.stringify({ type: 6 }) + String.fromCharCode(30));
        }
      }, 15000);
    };
    
    state.ws.onmessage = (event) => {
      const msg = event.data;
      if (msg.includes('"type":6')) {
        // Gói tin Ping của server, bỏ qua không in log để tránh rác log
      } else {
        addLog('Nhận phản hồi LRS: ' + msg.replace(/\u001e/g, ''), 'info');
      }
    };
    
    state.ws.onerror = (error) => {
      addLog('Lỗi kết nối WebSocket LRS: ' + (error.message || 'Không rõ nguyên nhân'), 'error');
    };
    
    state.ws.onclose = (event) => {
      addLog(`Đã đóng kết nối WebSocket LRS. Mã đóng: ${event.code}`, 'info');
      if (state.wsPingInterval) {
        clearInterval(state.wsPingInterval);
        state.wsPingInterval = null;
      }
    };
  } catch (e) {
    addLog('Không thể khởi tạo kết nối WebSocket LRS: ' + e.message, 'error');
  }
}

// Đóng kết nối WebSocket LRS
function stopWebSocket() {
  if (state.ws) {
    state.ws.close();
    state.ws = null;
  }
  if (state.wsPingInterval) {
    clearInterval(state.wsPingInterval);
    state.wsPingInterval = null;
  }
}

