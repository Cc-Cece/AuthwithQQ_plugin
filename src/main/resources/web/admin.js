document.addEventListener('DOMContentLoaded', () => {
    const playersTableBody = document.querySelector('#playersTable tbody');
    const botsTableBody = document.querySelector('#botsTable tbody');
    const playersMessage = document.getElementById('players-message');
    const botsMessage = document.getElementById('bots-message');
    // Removed pluginConfigPre and configMessage as they are no longer needed

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
            // configMessage.textContent = '未提供 API Token，无法加载配置数据。'; // Removed
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
        .then(allData => {
            playersTableBody.innerHTML = ''; // Clear existing data
            botsTableBody.innerHTML = '';
            
            const realPlayers = allData.filter(p => p["bot.is_bot"] !== "true");
            const bots = allData.filter(p => p["bot.is_bot"] === "true");

            if (realPlayers.length === 0) {
                playersMessage.textContent = '没有已绑定的常规玩家。';
            } else {
                playersMessage.textContent = '';
                realPlayers.forEach(player => {
                    const row = playersTableBody.insertRow();
                    row.insertCell().textContent = player.UUID;
                    row.insertCell().textContent = player.Name;
                    row.insertCell().textContent = player.QQ;
                    row.insertCell().textContent = new Date(parseInt(player.Created)).toLocaleString();

                    const actionCell = row.insertCell();
                    const editButton = document.createElement('button');
                    editButton.textContent = '编辑';
                    editButton.className = 'btn-edit';
                    editButton.onclick = () => window.location.href = `admin_edit_player.html?uuid=${player.UUID}&token=${apiToken}`;
                    actionCell.appendChild(editButton);

                    const unbindButton = document.createElement('button');
                    unbindButton.textContent = '解绑';
                    unbindButton.className = 'btn-unbind';
                    unbindButton.onclick = () => unbindPlayer(player.UUID);
                    actionCell.appendChild(unbindButton);
                });
            }

            if (bots.length === 0) {
                botsMessage.textContent = '没有已创建的假人。';
            } else {
                botsMessage.textContent = '';
                bots.forEach(bot => {
                    const row = botsTableBody.insertRow();
                    row.insertCell().textContent = bot.UUID;
                    row.insertCell().textContent = bot.Name;
                    row.insertCell().textContent = bot.Owner_uuid || bot["bot.owner_uuid"] || "未知";
                    row.insertCell().textContent = new Date(parseInt(bot.Created)).toLocaleString();

                    const actionCell = row.insertCell();
                    const editButton = document.createElement('button');
                    editButton.textContent = '编辑';
                    editButton.className = 'btn-edit';
                    editButton.onclick = () => window.location.href = `admin_edit_player.html?uuid=${bot.UUID}&token=${apiToken}`;
                    actionCell.appendChild(editButton);

                    // Note: No 'Unbind' for bots since they don't have a QQ to unbind.
                    // Could potentially add a delete button here if the API supported it.
                });
            }
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

    // Removed fetchConfig() function as it is no longer needed

    // Initial load
    fetchPlayers();
    // Removed fetchConfig() call

    // --- Handle Add/Modify Binding Form ---
    const bindForm = document.getElementById('bindForm');
    const playerIdentifierInput = document.getElementById('playerIdentifier');
    const qqNumberInput = document.getElementById('qqNumber');
    const bindMessage = document.getElementById('bind-message');

    if (bindForm) {
        bindForm.addEventListener('submit', (event) => {
            event.preventDefault(); // Prevent default form submission

            const playerIdentifier = playerIdentifierInput.value.trim();
            const qqNumber = qqNumberInput.value.trim();

            if (!playerIdentifier || !qqNumber) {
                bindMessage.textContent = '玩家标识符和QQ号码不能为空。';
                bindMessage.style.color = 'red';
                return;
            }

            if (isNaN(qqNumber) || parseInt(qqNumber) <= 0) {
                bindMessage.textContent = 'QQ号码必须是有效的正整数。';
                bindMessage.style.color = 'red';
                return;
            }

            fetch('/api/admin/bind', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-API-Token': apiToken
                },
                body: JSON.stringify({ playerIdentifier: playerIdentifier, qq: parseInt(qqNumber) })
            })
            .then(response => {
                if (response.status === 401) {
                    throw new Error('Unauthorized. 请检查您的 API Token。');
                }
                return response.json();
            })
            .then(result => {
                if (result.success) {
                    bindMessage.textContent = result.message || `玩家 ${playerIdentifier} 已成功绑定到 QQ ${qqNumber}。`;
                    bindMessage.style.color = 'green';
                    playerIdentifierInput.value = ''; // Clear form
                    qqNumberInput.value = '';
                    fetchPlayers(); // Refresh the players list
                } else {
                    bindMessage.textContent = `绑定失败: ${result.error || '未知错误'}`;
                    bindMessage.style.color = 'red';
                }
            })
            .catch(error => {
                console.error('Error adding/modifying binding:', error);
                bindMessage.textContent = `操作失败: ${error.message}`;
                bindMessage.style.color = 'red';
            });
        });
    }
});
