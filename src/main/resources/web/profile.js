document.addEventListener('DOMContentLoaded', () => {
    const profileForm = document.getElementById('profileForm');
    const tokenInput = document.getElementById('token');
    const uuidInput = document.getElementById('uuid');
    const playerNameInput = document.getElementById('playerName');
    const currentQqInput = document.getElementById('currentQq');
    const newQqInput = document.getElementById('newQq');
    const customFieldsContainer = document.getElementById('customFieldsContainer');
    const messageElement = document.getElementById('message');
    const loadingElement = document.getElementById('loading');

    // Parse URL parameters for token
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token');

    if (!token) {
        messageElement.textContent = '错误: 缺少会话令牌。';
        messageElement.style.color = 'red';
        return;
    }
    tokenInput.value = token;

    // --- Fetch player profile data ---
    function fetchProfile() {
        loadingElement.style.display = 'block';
        fetch(`/api/profile?token=${token}`)
            .then(response => {
                if (response.status === 401) {
                    throw new Error('无效或已过期的会话令牌。');
                }
                return response.json();
            })
            .then(profile => {
                uuidInput.value = profile.uuid;
                playerNameInput.value = profile.name;
                currentQqInput.value = profile.qq;
                newQqInput.value = profile.qq; // Pre-fill new QQ with current QQ

                // Clear previous custom fields
                customFieldsContainer.innerHTML = '';

                // Fetch custom field definitions and inject
                fetch('/api/meta')
                    .then(response => response.json())
                    .then(customFieldsDefs => {
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
                        console.error('Error fetching custom field definitions:', error);
                        messageElement.textContent = `加载自定义字段定义失败: ${error.message}`;
                        messageElement.style.color = 'red';
                    });
            })
            .catch(error => {
                console.error('Error fetching profile:', error);
                messageElement.textContent = `加载个人资料失败: ${error.message}`;
                messageElement.style.color = 'red';
            })
            .finally(() => {
                loadingElement.style.display = 'none';
            });
    }

    // --- Handle form submission for profile update ---
    profileForm.addEventListener('submit', (event) => {
        event.preventDefault();
        loadingElement.style.display = 'block';

        const data = {
            token: tokenInput.value,
            qq: parseInt(newQqInput.value),
            meta: {}
        };

        // Collect custom field data
        customFieldsContainer.querySelectorAll('input').forEach(input => {
            if (input.name !== 'newQq') { // Exclude newQq as it's directly in data.qq
                data.meta[input.name] = input.value;
            }
        });
        
        // Remove empty meta fields
        for (const key in data.meta) {
            if (data.meta.hasOwnProperty(key) && (data.meta[key] === null || data.meta[key] === '')) {
                delete data.meta[key];
            }
        }

        fetch('/api/profile/update', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data)
        })
        .then(response => response.json())
        .then(result => {
            if (result.success) {
                messageElement.textContent = result.message || '个人资料更新成功！';
                messageElement.style.color = 'green';
                // Refresh profile data to show updated current QQ
                fetchProfile();
            } else {
                messageElement.textContent = result.error || '更新失败。';
                messageElement.style.color = 'red';
            }
        })
        .catch(error => {
            console.error('Error updating profile:', error);
            messageElement.textContent = `更新失败: ${error.message}`;
            messageElement.style.color = 'red';
        })
        .finally(() => {
            loadingElement.style.display = 'none';
        });
    });

    // Initial fetch of profile data
    fetchProfile();
});
