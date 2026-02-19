document.addEventListener('DOMContentLoaded', () => {
    const playersTableBody = document.querySelector('#playersTable tbody');
    const playersMessage = document.getElementById('players-message');
    const pluginConfigPre = document.getElementById('pluginConfig');
    const configMessage = document.getElementById('config-message');

    // Function to get API Token from URL or Cookie
    function getApiToken() {
        const urlParams = new URLSearchParams(window.location.search);
        let token = urlParams.get('token');
        if (!token) {
            token = localStorage.getItem('apiToken'); // Try to get from localStorage
        }
        return token;
    }

    const apiToken = getApiToken();

    // If no token, prompt user
    if (!apiToken) {
        const userToken = prompt('请输入 API Token 以访问管理控制台:');
        if (userToken) {
            localStorage.setItem('apiToken', userToken); // Store for future use
            location.reload(); // Reload to apply token
        } else {
            playersMessage.textContent = '未提供 API Token，无法加载玩家数据。';
            configMessage.textContent = '未提供 API Token，无法加载配置数据。';
            return;
        }
    }

    // --- Fetch and display Players Data ---
    function fetchPlayers() {
        fetch('/api/players', {
            headers: {
                'X-API-Token': apiToken
            }
        })
        .then(response => {
            if (response.status === 401) {
                throw new Error('Unauthorized. 请检查您的 API Token。');
            }
            return response.json();
        })
        .then(players => {
            playersTableBody.innerHTML = ''; // Clear existing data
            if (players.length === 0) {
                playersMessage.textContent = '没有已绑定的玩家。';
                return;
            }
            players.forEach(player => {
                const row = playersTableBody.insertRow();
                row.insertCell().textContent = player.UUID;
                row.insertCell().textContent = player.Name;
                row.insertCell().textContent = player.QQ;
                row.insertCell().textContent = new Date(parseInt(player.Created)).toLocaleString();

                const actionCell = row.insertCell();
                const unbindButton = document.createElement('button');
                unbindButton.textContent = '解绑';
                unbindButton.className = 'btn-unbind';
                unbindButton.onclick = () => unbindPlayer(player.UUID);
                actionCell.appendChild(unbindButton);
            });
            playersMessage.textContent = ''; // Clear message on success
        })
        .catch(error => {
            console.error('Error fetching players:', error);
            playersMessage.textContent = `加载玩家数据失败: ${error.message}`;
            playersMessage.style.color = 'red';
        });
    }

    // --- Unbind Player Function ---
    function unbindPlayer(uuid) {
        if (!confirm(`确定要解绑玩家 ${uuid} 吗？`)) {
            return;
        }

        fetch('/api/unbind', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-API-Token': apiToken
            },
            body: JSON.stringify({ uuid: uuid })
        })
        .then(response => {
            if (response.status === 401) {
                throw new Error('Unauthorized. 请检查您的 API Token。');
            }
            return response.json();
        })
        .then(result => {
            if (result.success) {
                alert(`玩家 ${uuid} 解绑成功！`);
                fetchPlayers(); // Refresh players list
            } else {
                alert(`解绑失败: ${result.error || '未知错误'}`);
            }
        })
        .catch(error => {
            console.error('Error unbinding player:', error);
            alert(`解绑操作失败: ${error.message}`);
        });
    }

    // --- Fetch and display Plugin Config ---
    function fetchConfig() {
        fetch('/api/config', {
            headers: {
                'X-API-Token': apiToken
            }
        })
        .then(response => {
            if (response.status === 401) {
                throw new Error('Unauthorized. 请检查您的 API Token。');
            }
            return response.json();
        })
        .then(config => {
            pluginConfigPre.textContent = JSON.stringify(config, null, 2);
            configMessage.textContent = ''; // Clear message on success
        })
        .catch(error => {
            console.error('Error fetching config:', error);
            configMessage.textContent = `加载配置失败: ${error.message}`;
            configMessage.style.color = 'red';
        });
    }

    // Initial load
    fetchPlayers();
    fetchConfig();
});
