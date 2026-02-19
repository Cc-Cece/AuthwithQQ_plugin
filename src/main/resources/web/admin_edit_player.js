document.addEventListener('DOMContentLoaded', () => {
    const editPlayerForm = document.getElementById('editPlayerForm');
    const uuidInput = document.getElementById('uuid');
    const apiTokenInput = document.getElementById('apiToken');
    const playerNameInput = document.getElementById('playerName');
    const currentQqInput = document.getElementById('currentQq');
    const newQqInput = document.getElementById('newQq');
    const customFieldsContainer = document.getElementById('customFieldsContainer');
    const messageElement = document.getElementById('message');
    const loadingElement = document.getElementById('loading');
    const playerInfoElement = document.getElementById('playerInfo');

    // Parse URL parameters for UUID and API Token
    const urlParams = new URLSearchParams(window.location.search);
    const playerUuid = urlParams.get('uuid');
    const apiToken = urlParams.get('token');

    if (!playerUuid || !apiToken) {
        messageElement.textContent = '错误: 缺少玩家 UUID 或 API Token。';
        messageElement.style.color = 'red';
        return;
    }

    uuidInput.value = playerUuid;
    apiTokenInput.value = apiToken;

    // --- Fetch player profile data and custom field definitions ---
    function fetchPlayerProfileAndMeta() {
        loadingElement.style.display = 'block';
        Promise.all([
            fetch(`/api/profile?uuid=${playerUuid}`, { headers: { 'X-API-Token': apiToken } }),
            fetch('/api/meta') // Fetch custom field definitions
        ])
        .then(async ([profileResponse, metaResponse]) => {
            if (profileResponse.status === 401 || metaResponse.status === 401) {
                throw new Error('Unauthorized. 请检查您的 API Token。');
            }
            const profile = await profileResponse.json();
            const customFieldsDefs = await metaResponse.json();

            // Populate basic info
            playerInfoElement.textContent = `当前编辑: ${profile.name} (${profile.uuid})`;
            playerNameInput.value = profile.name;
            currentQqInput.value = profile.qq;
            newQqInput.value = profile.qq; // Pre-fill new QQ with current QQ

            // Clear previous custom fields
            customFieldsContainer.innerHTML = '';

            // Dynamically create and populate custom fields
            customFieldsDefs.forEach(fieldDef => {
                const formGroup = document.createElement('div');
                formGroup.className = 'form-group';

                const label = document.createElement('label');
                label.setAttribute('for', fieldDef.name);
                label.textContent = fieldDef.label + ':';
                formGroup.appendChild(label);

                const input = document.createElement('input');
                input.setAttribute('type', fieldDef.type || 'text');
                input.setAttribute('id', fieldDef.name);
                input.setAttribute('name', fieldDef.name);
                if (fieldDef.required) {
                    input.setAttribute('required', 'true');
                }
                // Pre-fill with existing meta data
                if (profile.meta && profile.meta[fieldDef.name]) {
                    input.value = profile.meta[fieldDef.name];
                }
                formGroup.appendChild(input);
                customFieldsContainer.appendChild(formGroup);
            });
        })
        .catch(error => {
            console.error('Error fetching player profile or meta:', error);
            messageElement.textContent = `加载玩家资料失败: ${error.message}`;
            messageElement.style.color = 'red';
        })
        .finally(() => {
            loadingElement.style.display = 'none';
        });
    }

    // --- Handle form submission for profile update ---
    editPlayerForm.addEventListener('submit', (event) => {
        event.preventDefault();
        loadingElement.style.display = 'block';

        const data = {
            token: apiTokenInput.value, // This is X-API-Token for admin update
            uuid: uuidInput.value,
            qq: parseInt(newQqInput.value),
            meta: {}
        };

        // Collect custom field data
        customFieldsContainer.querySelectorAll('input').forEach(input => {
            // Exclude newQq as it's directly in data.qq, and also original player inputs
            if (input.name !== 'newQq' && input.id !== 'playerName' && input.id !== 'currentQq') {
                data.meta[input.name] = input.value;
            }
        });
        
        // Remove empty meta fields
        for (const key in data.meta) {
            if (data.meta.hasOwnProperty(key) && (data.meta[key] === null || data.meta[key] === '')) {
                delete data.meta[key];
            }
        }

        fetch('/api/profile/update', { // Using the same API as player profile update
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-API-Token': apiTokenInput.value // Admin API Token
            },
            body: JSON.stringify(data)
        })
        .then(response => {
            if (response.status === 401) {
                throw new Error('Unauthorized. 请检查您的 API Token。');
            }
            return response.json();
        })
        .then(result => {
            if (result.success) {
                messageElement.textContent = result.message || '玩家资料更新成功！';
                messageElement.style.color = 'green';
                fetchPlayerProfileAndMeta(); // Refresh profile data to show updated current QQ
            } else {
                messageElement.textContent = result.error || '更新失败。';
                messageElement.style.color = 'red';
            }
        })
        .catch(error => {
            console.error('Error updating player profile:', error);
            messageElement.textContent = `更新失败: ${error.message}`;
            messageElement.style.color = 'red';
        })
        .finally(() => {
            loadingElement.style.display = 'none';
        });
    });

    // Initial fetch of player profile data and custom field definitions
    fetchPlayerProfileAndMeta();
});
