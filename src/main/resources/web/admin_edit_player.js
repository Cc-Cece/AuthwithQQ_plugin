document.addEventListener('DOMContentLoaded', () => {
    const editPlayerForm = document.getElementById('editPlayerForm');
    const uuidInput = document.getElementById('uuid');
    const apiTokenInput = document.getElementById('apiToken');
    const playerNameInput = document.getElementById('playerName');
    const currentQqInput = document.getElementById('currentQq');
    const newQqInput = document.getElementById('newQq');
    const ownerUuidInput = document.getElementById('ownerUuid');
    const customFieldsContainer = document.getElementById('customFieldsContainer');
    const messageElement = document.getElementById('message');
    const loadingElement = document.getElementById('loading');
    const playerInfoElement = document.getElementById('playerInfo');

    const realPlayerOnlyElements = document.querySelectorAll('.real-player-only');
    const botOnlyElements = document.querySelectorAll('.bot-only');

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

    // Store profile data globally for form submission
    let profile = null;

    // --- Fetch player profile data and custom field definitions ---
    function fetchPlayerProfileAndMeta() {
        loadingElement.style.display = 'block';
        // First fetch profile to determine player type
        fetch(`/api/profile?uuid=${playerUuid}`, { headers: { 'X-API-Token': apiToken } })
        .then(async (profileResponse) => {
            if (profileResponse.status === 401) {
                throw new Error('Unauthorized. 请检查您的 API Token。');
            }
            profile = await profileResponse.json();

            // Determine player type
            const isBot = profile.meta && profile.meta["bot.is_bot"] === "true";
            const fieldType = isBot ? "bot" : "player";

            // Fetch custom field definitions based on player type
            return fetch(`/api/meta?type=${fieldType}`, { headers: { 'X-API-Token': apiToken } })
                .then(async (metaResponse) => {
                    if (metaResponse.status === 401) {
                        throw new Error('Unauthorized. 请检查您的 API Token。');
                    }
                    const customFieldsDefs = await metaResponse.json();
                    return { profile, customFieldsDefs, isBot };
                });
        })
        .then(({ profile, customFieldsDefs, isBot }) => {
            // Populate basic info
            playerInfoElement.textContent = `当前编辑: ${profile.name || 'Unknown'} (${profile.uuid})`;
            playerNameInput.value = profile.name || '';
            currentQqInput.value = profile.qq || 0;
            newQqInput.value = profile.qq || 0; // Pre-fill new QQ with current QQ

            // Toggle UI for Bot or Real Player
            if (isBot) {
                document.title = "编辑假人资料";
                document.querySelector('h1').innerText = "编辑假人资料";
                realPlayerOnlyElements.forEach(el => el.style.display = 'none');
                botOnlyElements.forEach(el => el.style.display = 'block');
                // Make player name editable for bots
                playerNameInput.readOnly = false;
                playerNameInput.placeholder = "假人名称";
                if (profile.meta && profile.meta["bot.owner_uuid"]) {
                    ownerUuidInput.value = profile.meta["bot.owner_uuid"];
                }
            } else {
                document.title = "编辑玩家资料";
                document.querySelector('h1').innerText = "编辑玩家资料";
                realPlayerOnlyElements.forEach(el => el.style.display = 'block');
                botOnlyElements.forEach(el => el.style.display = 'none');
                // Keep player name read-only for real players
                playerNameInput.readOnly = true;
            }

            // Clear previous custom fields
            customFieldsContainer.innerHTML = '';

            // Dynamically create and populate custom fields
            if (Array.isArray(customFieldsDefs) && customFieldsDefs.length > 0) {
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
            }
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
            qq: parseInt(newQqInput.value || 0),
            meta: {}
        };

        // Check if this is a bot
        const isBot = profile && profile.meta && profile.meta["bot.is_bot"] === "true";
        
        // If it's a bot, update bot name if changed
        if (isBot && playerNameInput.value && playerNameInput.value !== profile.name) {
            data.meta["bot.bot_name"] = playerNameInput.value;
        }

        // Collect custom field data from container
        customFieldsContainer.querySelectorAll('input').forEach(input => {
             data.meta[input.name] = input.value;
        });

        // Collect ownerUuid if it's a bot
        if (isBot && ownerUuidInput && ownerUuidInput.value) {
            data.meta["bot.owner_uuid"] = ownerUuidInput.value;
        }
        
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
