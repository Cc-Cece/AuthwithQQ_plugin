document.addEventListener('DOMContentLoaded', () => {
    const tpsValue = document.getElementById('tps-value');
    const memoryProgress = document.getElementById('memory-progress');
    const memoryValue = document.getElementById('memory-value');
    const playersProgress = document.getElementById('players-progress');
    const playersValue = document.getElementById('players-value');
    const onlinePlayersList = document.getElementById('online-players-list');

    // Chart.js setup for TPS
    const tpsCtx = document.getElementById('tpsChart').getContext('2d');
    const tpsChart = new Chart(tpsCtx, {
        type: 'line',
        data: {
            labels: [], // Time labels
            datasets: [{
                label: 'TPS',
                data: [], // TPS values
                borderColor: 'rgb(75, 192, 192)',
                tension: 0.1,
                fill: false
            }]
        },
        options: {
            animation: false,
            scales: {
                y: {
                    beginAtZero: true,
                    max: 20 // Max TPS for Minecraft
                }
            }
        }
    });

    function updateDashboard() {
        fetch('/api/status')
            .then(response => response.json())
            .then(data => {
                // Update TPS
                tpsValue.textContent = `当前: ${data.tps.toFixed(2)}`;
                tpsChart.data.labels.push(new Date().toLocaleTimeString());
                tpsChart.data.datasets[0].data.push(data.tps);
                if (tpsChart.data.labels.length > 20) { // Keep last 20 data points
                    tpsChart.data.labels.shift();
                    tpsChart.data.datasets[0].data.shift();
                }
                tpsChart.update();

                // Update Memory
                const memoryUsed = data.ram_total - data.ram_free;
                const memoryPercent = (memoryUsed / data.ram_total) * 100;
                memoryProgress.style.width = `${memoryPercent}%`;
                memoryValue.textContent = `使用: ${memoryUsed}MB / 总计: ${data.ram_total}MB (${memoryPercent.toFixed(1)}%)`;

                // Update Online Players
                const playersPercent = (data.online_players / data.max_players) * 100;
                playersProgress.style.width = `${playersPercent}%`;
                playersValue.textContent = `在线: ${data.online_players} / 最大: ${data.max_players} (${playersPercent.toFixed(1)}%)`;

                // Update Online Players List (assuming data.online_player_names exists from /api/status - need to add this to backend)
                // For now, I'll use a placeholder or assume `online_players` in data refers to the count only
                onlinePlayersList.innerHTML = '';
                if (data.online_player_names && Array.isArray(data.online_player_names)) {
                    data.online_player_names.forEach(player => {
                        const li = document.createElement('li');
                        li.textContent = player;
                        onlinePlayersList.appendChild(li);
                    });
                } else {
                    const li = document.createElement('li');
                    li.textContent = `当前在线玩家数量: ${data.online_players}`;
                    onlinePlayersList.appendChild(li);
                }

            })
            .catch(error => {
                console.error('Error fetching server status:', error);
                tpsValue.textContent = '获取失败';
                memoryValue.textContent = '获取失败';
                playersValue.textContent = '获取失败';
            });
    }

    // Initial update and then every 5 seconds
    updateDashboard();
    setInterval(updateDashboard, 5000);
});
