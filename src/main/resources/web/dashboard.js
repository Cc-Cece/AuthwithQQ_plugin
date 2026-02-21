document.addEventListener('DOMContentLoaded', () => {
    const { Toast, Loading, Utils } = window.MaterialComponents;
    
    // 元素引用
    const tpsValue = document.getElementById('tps-value');
    const tpsBadge = document.getElementById('tps-badge');
    const memoryProgress = document.getElementById('memory-progress');
    const memoryLabel = document.getElementById('memory-label');
    const memoryProgressValue = document.getElementById('memory-progress-value');
    const playersProgress = document.getElementById('players-progress');
    const playersLabel = document.getElementById('players-label');
    const playersValue = document.getElementById('players-value');
    const onlinePlayersList = document.getElementById('online-players-list');
    const refreshBtn = document.getElementById('refreshBtn');
    const refreshIcon = document.getElementById('refreshIcon');
    const refreshText = document.getElementById('refreshText');
    const autoRefreshToggle = document.getElementById('autoRefreshToggle');
    const lastUpdate = document.getElementById('lastUpdate');
    
    // 状态
    let autoRefreshInterval = null;
    let isRefreshing = false;
    let maxChartPoints = 50; // 默认限制图表数据点数量
    const memoryHistory = []; // 内存使用历史
    const tpsTimeRange = document.getElementById('tpsTimeRange');
    
    // 格式化运行时间
    function formatUptime(millis) {
        const seconds = Math.floor(millis / 1000);
        const minutes = Math.floor(seconds / 60);
        const hours = Math.floor(minutes / 60);
        const days = Math.floor(hours / 24);
        
        if (days > 0) {
            return `${days}天 ${hours % 24}小时 ${minutes % 60}分钟`;
        } else if (hours > 0) {
            return `${hours}小时 ${minutes % 60}分钟`;
        } else if (minutes > 0) {
            return `${minutes}分钟 ${seconds % 60}秒`;
        } else {
            return `${seconds}秒`;
        }
    }

    // 格式化时间（相对时间）
    function formatRelativeTime(timestamp) {
        const now = Date.now();
        const diff = now - timestamp;
        const seconds = Math.floor(diff / 1000);
        const minutes = Math.floor(seconds / 60);
        const hours = Math.floor(minutes / 60);
        
        if (seconds < 60) {
            return `${seconds}秒前`;
        } else if (minutes < 60) {
            return `${minutes}分钟前`;
        } else if (hours < 24) {
            return `${hours}小时前`;
        } else {
            const date = new Date(timestamp);
            return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        }
    }

    // Chart.js 设置 - TPS 图表
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

    // Chart.js 设置 - 内存图表
    const memoryCtx = document.getElementById('memoryChart').getContext('2d');
    const memoryChart = new Chart(memoryCtx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [{
                label: '内存使用率 (%)',
                data: [],
                borderColor: 'rgb(217, 48, 37)',
                backgroundColor: 'rgba(217, 48, 37, 0.1)',
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
                    titleFont: { size: 14 },
                    bodyFont: { size: 13 },
                    callbacks: {
                        label: function(context) {
                            return '内存使用率: ' + context.parsed.y.toFixed(1) + '%';
                        }
                    }
                }
            },
            scales: {
                x: {
                    display: true,
                    grid: { display: false },
                    ticks: {
                        maxTicksLimit: 10,
                        font: { size: 11 }
                    }
                },
                y: {
                    beginAtZero: true,
                    max: 100,
                    grid: {
                        color: 'rgba(0, 0, 0, 0.05)'
                    },
                    ticks: {
                        stepSize: 20,
                        font: { size: 11 },
                        callback: function(value) {
                            return value + '%';
                        }
                    }
                }
            }
        }
    });

    // TPS 时间范围选择器
    tpsTimeRange.addEventListener('change', (e) => {
        maxChartPoints = parseInt(e.target.value);
        // 限制现有数据点
        if (tpsChart.data.labels.length > maxChartPoints) {
            const removeCount = tpsChart.data.labels.length - maxChartPoints;
            tpsChart.data.labels.splice(0, removeCount);
            tpsChart.data.datasets[0].data.splice(0, removeCount);
            tpsChart.update('none');
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
            if (tpsChart.data.labels.length > maxChartPoints) {
                    tpsChart.data.labels.shift();
                    tpsChart.data.datasets[0].data.shift();
                }
            tpsChart.update('none'); // 'none' 禁用动画以提高性能

            // 更新内存
                const memoryUsed = data.ram_total - data.ram_free;
            const memoryPercent = Math.round((memoryUsed / data.ram_total) * 100);

            // 更新内存进度条（在TPS卡片内）
                memoryProgress.style.width = `${memoryPercent}%`;
            memoryLabel.textContent = `${memoryPercent}%`;
            memoryProgressValue.textContent = `使用: ${memoryUsed}MB / 总计: ${data.ram_total}MB`;
            
            // 根据内存使用率设置进度条颜色
            if (memoryPercent >= 90) {
                memoryProgress.className = 'progress-bar progress-danger';
            } else if (memoryPercent >= 70) {
                memoryProgress.className = 'progress-bar progress-warning';
            } else {
                memoryProgress.className = 'progress-bar progress-success';
            }

            // 更新内存历史图表
            memoryChart.data.labels.push(timeLabel);
            memoryChart.data.datasets[0].data.push(memoryPercent);
            
            // 限制内存图表数据点数量
            if (memoryChart.data.labels.length > maxChartPoints) {
                memoryChart.data.labels.shift();
                memoryChart.data.datasets[0].data.shift();
            }
            memoryChart.update('none');
            
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

            // 更新服务器信息
            if (data.uptime_millis !== undefined) {
                document.getElementById('uptime-value').textContent = formatUptime(data.uptime_millis);
            }
            if (data.server_version !== undefined) {
                document.getElementById('server-version').textContent = data.server_version || '--';
            }
            if (data.bukkit_version !== undefined) {
                document.getElementById('bukkit-version').textContent = data.bukkit_version || '--';
            }
            if (data.minecraft_version !== undefined) {
                document.getElementById('minecraft-version').textContent = data.minecraft_version || '--';
            }
            if (data.world_count !== undefined) {
                document.getElementById('world-count').textContent = data.world_count || '--';
            }

            // 更新实体统计
            if (data.total_entities !== undefined) {
                document.getElementById('total-entities').textContent = data.total_entities || '--';
            }
            if (data.player_entities !== undefined) {
                document.getElementById('player-entities').textContent = data.player_entities || '--';
            }
            if (data.living_entities !== undefined) {
                document.getElementById('living-entities').textContent = data.living_entities || '--';
            }
            if (data.item_entities !== undefined) {
                document.getElementById('item-entities').textContent = data.item_entities || '--';
            }
            if (data.other_entities !== undefined) {
                document.getElementById('other-entities').textContent = data.other_entities || '--';
            }

            // 更新今日在线统计
            if (data.today_unique_players !== undefined) {
                document.getElementById('today-unique-players').textContent = data.today_unique_players || '0';
            }
            if (data.today_total_online_time_ms !== undefined) {
                const totalTime = formatUptime(data.today_total_online_time_ms);
                document.getElementById('today-total-online-time').textContent = totalTime;
            }

            // 更新今日玩家在线时长列表
            const playerTimesList = document.getElementById('today-player-times-list');
            if (data.today_player_online_times !== undefined) {
                if (Array.isArray(data.today_player_online_times) && data.today_player_online_times.length > 0) {
                    // 按在线时长排序（从高到低）
                    const sortedTimes = [...data.today_player_online_times].sort((a, b) => b.online_time_ms - a.online_time_ms);
                    playerTimesList.innerHTML = sortedTimes.map(item => {
                        const duration = formatUptime(item.online_time_ms);
                        return `
                            <li class="player-time-item">
                                <span class="player-time-name">${item.player_name}</span>
                                <span class="player-time-duration">${duration}</span>
                            </li>
                        `;
                    }).join('');
                } else {
                    playerTimesList.innerHTML = '<li class="player-time-item empty">暂无玩家数据</li>';
                }
            } else {
                playerTimesList.innerHTML = '<li class="player-time-item empty">加载中...</li>';
            }

            // 更新最近玩家活动
            const activitiesList = document.getElementById('recent-activities-list');
            if (data.recent_activities && Array.isArray(data.recent_activities)) {
                if (data.recent_activities.length === 0) {
                    activitiesList.innerHTML = '<li class="activity-item empty">暂无活动记录</li>';
                } else {
                    // 按时间倒序显示（最新的在前）
                    const sortedActivities = [...data.recent_activities].reverse();
                    activitiesList.innerHTML = sortedActivities.map(activity => {
                        const icon = activity.activity_type === 'join' ? '🟢' : '🔴';
                        const typeClass = activity.activity_type === 'join' ? 'join' : 'quit';
                        const typeText = activity.activity_type === 'join' ? '加入' : '离开';
                        const relativeTime = formatRelativeTime(activity.timestamp);
                        
                        return `
                            <li class="activity-item ${typeClass}">
                                <span class="activity-icon">${icon}</span>
                                <div class="activity-content">
                                    <span>
                                        <span class="activity-player">${activity.player_name}</span>
                                        <span style="margin: 0 8px; color: var(--text-secondary);">${typeText}</span>
                                    </span>
                                    <span class="activity-time">${relativeTime}</span>
                                </div>
                            </li>
                        `;
                    }).join('');
                }
            } else {
                activitiesList.innerHTML = '<li class="activity-item empty">加载失败</li>';
            }
            
            // 更新最后刷新时间
            lastUpdate.textContent = `最后更新: ${new Date().toLocaleTimeString('zh-CN')}`;
            
        } catch (error) {
            console.error('Error fetching server status:', error);
            Toast.error('获取服务器状态失败');
            tpsValue.textContent = '获取失败';
            memoryProgressValue.textContent = '获取失败';
            playersValue.textContent = '获取失败';
        } finally {
            isRefreshing = false;
            refreshIcon.style.animation = '';
        }
    }


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
    updateDashboard();
    
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
