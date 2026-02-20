document.addEventListener('DOMContentLoaded', () => {
    const { Toast, Loading, Utils } = window.MaterialComponents;
    
    // 元素引用
    const tpsValue = document.getElementById('tps-value');
    const tpsBadge = document.getElementById('tps-badge');
    const memoryProgress = document.getElementById('memory-progress');
    const memoryLabel = document.getElementById('memory-label');
    const memoryValue = document.getElementById('memory-value');
    const playersProgress = document.getElementById('players-progress');
    const playersLabel = document.getElementById('players-label');
    const playersValue = document.getElementById('players-value');
    const onlinePlayersList = document.getElementById('online-players-list');
    const botsList = document.getElementById('bots-list');
    const addBotForm = document.getElementById('add-bot-form');
    const botNameInput = document.getElementById('bot-name-input');
    const refreshBtn = document.getElementById('refreshBtn');
    const refreshIcon = document.getElementById('refreshIcon');
    const refreshText = document.getElementById('refreshText');
    const autoRefreshToggle = document.getElementById('autoRefreshToggle');
    const lastUpdate = document.getElementById('lastUpdate');
    const botsCard = document.getElementById('bots-card');
    const botsLoginPrompt = document.getElementById('bots-login-prompt');
    const botsContent = document.getElementById('bots-content');
    
    // 状态
    let sessionToken = localStorage.getItem('session_token');
    let autoRefreshInterval = null;
    let isRefreshing = false;
    const MAX_CHART_POINTS = 50; // 限制图表数据点数量
    
    // Chart.js 设置
    const tpsCtx = document.getElementById('tpsChart').getContext('2d');
    const tpsChart = new Chart(tpsCtx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                label: 'TPS',
                data: [],
                borderColor: 'rgb(26, 115, 232)',
                backgroundColor: 'rgba(26, 115, 232, 0.1)',
                tension: 0.4,
                fill: true,
                pointRadius: 0,
                pointHoverRadius: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            interaction: {
                intersect: false,
                mode: 'index'
            },
            plugins: {
                legend: {
                    display: false
                },
                tooltip: {
                    backgroundColor: 'rgba(0, 0, 0, 0.8)',
                    padding: 12,
                    titleFont: {
                        size: 14
                    },
                    bodyFont: {
                        size: 13
                    }
                }
            },
            scales: {
                x: {
                    display: true,
                    grid: {
                        display: false
                    },
                    ticks: {
                        maxTicksLimit: 10,
                        font: {
                            size: 11
                        }
                    }
                },
                y: {
                    beginAtZero: true,
                    max: 20,
                    grid: {
                        color: 'rgba(0, 0, 0, 0.05)'
                    },
                    ticks: {
                        stepSize: 5,
                        font: {
                            size: 11
                        }
                    }
                }
            }
        }
    });

    // 更新看板数据
    async function updateDashboard() {
        if (isRefreshing) return;
        isRefreshing = true;
        
        // 更新刷新按钮状态
        refreshIcon.style.animation = 'spinner-spin 1s linear infinite';
        
        try {
            const response = await fetch('/api/status');
            if (!response.ok) {
                throw new Error('获取服务器状态失败');
            }
            
            const data = await response.json();
            
            // 更新 TPS
            const tps = data.tps.toFixed(2);
            tpsValue.textContent = `当前: ${tps}`;
            
            // TPS 状态徽章
            if (tps >= 19.5) {
                tpsBadge.textContent = '优秀';
                tpsBadge.className = 'card-badge badge-success';
            } else if (tps >= 18.0) {
                tpsBadge.textContent = '良好';
                tpsBadge.className = 'card-badge badge-warning';
            } else {
                tpsBadge.textContent = '警告';
                tpsBadge.className = 'card-badge badge-error';
            }
            
            // 更新 TPS 图表
            const now = new Date();
            const timeLabel = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`;
            tpsChart.data.labels.push(timeLabel);
            tpsChart.data.datasets[0].data.push(data.tps);
            
            // 限制数据点数量
            if (tpsChart.data.labels.length > MAX_CHART_POINTS) {
                tpsChart.data.labels.shift();
                tpsChart.data.datasets[0].data.shift();
            }
            tpsChart.update('none'); // 'none' 禁用动画以提高性能
            
            // 更新内存
            const memoryUsed = data.ram_total - data.ram_free;
            const memoryPercent = Math.round((memoryUsed / data.ram_total) * 100);
            memoryProgress.style.width = `${memoryPercent}%`;
            memoryLabel.textContent = `${memoryPercent}%`;
            memoryValue.textContent = `使用: ${memoryUsed}MB / 总计: ${data.ram_total}MB`;
            
            // 更新内存进度条颜色
            memoryProgress.className = 'progress-bar';
            if (memoryPercent >= 90) {
                memoryProgress.classList.add('progress-danger');
            } else if (memoryPercent >= 70) {
                memoryProgress.classList.add('progress-warning');
            } else {
                memoryProgress.classList.add('progress-success');
            }
            
            // 更新在线玩家
            const playersPercent = Math.round((data.online_players / data.max_players) * 100);
            playersProgress.style.width = `${playersPercent}%`;
            playersLabel.textContent = `${playersPercent}%`;
            playersValue.textContent = `在线: ${data.online_players} / 最大: ${data.max_players}`;
            
            // 更新玩家列表
            onlinePlayersList.innerHTML = '';
            if (data.online_player_names && Array.isArray(data.online_player_names) && data.online_player_names.length > 0) {
                data.online_player_names.forEach(player => {
                    const li = document.createElement('li');
                    li.className = 'player-item';
                    li.textContent = player;
                    onlinePlayersList.appendChild(li);
                });
            } else {
                const li = document.createElement('li');
                li.className = 'player-item empty';
                li.textContent = '当前没有在线玩家';
                onlinePlayersList.appendChild(li);
            }
            
            // 更新最后刷新时间
            lastUpdate.textContent = `最后更新: ${new Date().toLocaleTimeString('zh-CN')}`;
            
        } catch (error) {
            console.error('Error fetching server status:', error);
            Toast.error('获取服务器状态失败');
            tpsValue.textContent = '获取失败';
            memoryValue.textContent = '获取失败';
            playersValue.textContent = '获取失败';
        } finally {
            isRefreshing = false;
            refreshIcon.style.animation = '';
        }
    }

    // 假人管理功能
    function checkBotAccess() {
        if (!sessionToken) {
            botsLoginPrompt.style.display = 'block';
            botsContent.style.display = 'none';
            return false;
        } else {
            botsLoginPrompt.style.display = 'none';
            botsContent.style.display = 'block';
            return true;
        }
    }

    async function fetchBots() {
        if (!checkBotAccess()) return;
        
        try {
            const response = await fetch(`/api/user/bots?token=${sessionToken}`);
            if (!response.ok) {
                if (response.status === 401) {
                    sessionToken = null;
                    localStorage.removeItem('session_token');
                    checkBotAccess();
                    return;
                }
                throw new Error('获取假人列表失败');
            }
            
            const bots = await response.json();
            botsList.innerHTML = '';
            
            if (bots.length === 0) {
                const li = document.createElement('li');
                li.className = 'bot-item empty';
                li.textContent = '您还没有绑定任何假人';
                botsList.appendChild(li);
            } else {
                bots.forEach(bot => {
                    const li = document.createElement('li');
                    li.className = 'bot-item';
                    li.innerHTML = `
                        <span class="bot-name">${bot.bot_name}</span>
                        <button class="btn-secondary btn-small remove-bot-btn" data-bot-uuid="${bot.bot_uuid}" aria-label="解绑假人">
                            解绑
                        </button>
                    `;
                    botsList.appendChild(li);
                });
            }
        } catch (error) {
            console.error('Error fetching bots:', error);
            Toast.error('加载假人列表失败');
        }
    }

    // 添加假人
    addBotForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const botName = botNameInput.value.trim();
        
        if (!botName) {
            Toast.error('请输入假人ID');
            return;
        }
        
        if (!sessionToken) {
            Toast.error('请先登录');
            return;
        }
        
        Loading.button(addBotForm.querySelector('button'), true);
        
        try {
            const response = await fetch('/api/user/bot/bind', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: sessionToken, bot_name: botName })
            });
            
            const data = await response.json();
            
            if (data.success) {
                Toast.success('假人添加成功');
                botNameInput.value = '';
                await fetchBots();
            } else {
                Toast.error(data.error || '添加失败');
            }
        } catch (error) {
            console.error('Error adding bot:', error);
            Toast.error('添加假人时出错');
        } finally {
            Loading.button(addBotForm.querySelector('button'), false);
        }
    });

    // 解绑假人
    botsList.addEventListener('click', async (e) => {
        if (e.target.classList.contains('remove-bot-btn')) {
            const botUuid = e.target.getAttribute('data-bot-uuid');
            const botName = e.target.closest('.bot-item').querySelector('.bot-name').textContent;
            
            if (!confirm(`确定要解绑假人 "${botName}" 吗？`)) {
                return;
            }
            
            Loading.button(e.target, true);
            
            try {
                const response = await fetch('/api/user/bot/unbind', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ token: sessionToken, bot_uuid: botUuid })
                });
                
                const data = await response.json();
                
                if (data.success) {
                    Toast.success('假人解绑成功');
                    await fetchBots();
                } else {
                    Toast.error(data.error || '解绑失败');
                }
            } catch (error) {
                console.error('Error removing bot:', error);
                Toast.error('解绑假人时出错');
            } finally {
                Loading.button(e.target, false);
            }
        }
    });

    // 手动刷新
    refreshBtn.addEventListener('click', () => {
        updateDashboard();
    });

    // 自动刷新控制
    autoRefreshToggle.addEventListener('change', (e) => {
        if (e.target.checked) {
            startAutoRefresh();
        } else {
            stopAutoRefresh();
        }
    });

    function startAutoRefresh() {
        stopAutoRefresh();
        autoRefreshInterval = setInterval(updateDashboard, 5000);
    }

    function stopAutoRefresh() {
        if (autoRefreshInterval) {
            clearInterval(autoRefreshInterval);
            autoRefreshInterval = null;
        }
    }

    // 初始化
    checkBotAccess();
    updateDashboard();
    fetchBots();
    
    if (autoRefreshToggle.checked) {
        startAutoRefresh();
    }
    
    // 页面可见性变化时控制自动刷新
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            stopAutoRefresh();
        } else if (autoRefreshToggle.checked) {
            startAutoRefresh();
            updateDashboard(); // 立即更新一次
        }
    });
    
    // 清理
    window.addEventListener('beforeunload', () => {
        stopAutoRefresh();
    });
});
