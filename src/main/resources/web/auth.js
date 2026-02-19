document.addEventListener('DOMContentLoaded', () => {
    const authForm = document.getElementById('authForm');
    const uuidInput = document.getElementById('uuid');
    const nameInput = document.getElementById('name');
    const verificationCodeInput = document.getElementById('verificationCode'); // ADDED
    const customFieldsContainer = document.getElementById('customFieldsContainer');
    const messageElement = document.getElementById('message');

    // Parse URL parameters for uuid, name, and verificationCode
    const urlParams = new URLSearchParams(window.location.search);
    const uuid = urlParams.get('uuid');
    const name = urlParams.get('name');
    const verificationCode = urlParams.get('verificationCode'); // ADDED

    if (uuid) {
        uuidInput.value = uuid;
    }
    if (name) {
        nameInput.value = decodeURIComponent(name);
    }
    if (verificationCode) { // ADDED
        verificationCodeInput.value = verificationCode; // ADDED
    }

    // Fetch custom fields from API and dynamically create form inputs
    fetch('/api/meta')
        .then(response => response.json())
        .then(customFields => {
            customFields.forEach(field => {
                const formGroup = document.createElement('div');
                formGroup.className = 'form-group';

                const label = document.createElement('label');
                label.setAttribute('for', field.name);
                label.textContent = field.label + ':';
                formGroup.appendChild(label);

                const input = document.createElement('input');
                input.setAttribute('type', field.type || 'text');
                input.setAttribute('id', field.name);
                input.setAttribute('name', field.name);
                if (field.required) {
                    input.setAttribute('required', 'true');
                }
                formGroup.appendChild(input);

                customFieldsContainer.appendChild(formGroup);
            });
        })
        .catch(error => {
            console.error('Error fetching custom fields:', error);
            messageElement.textContent = 'Error loading custom fields. Please try again later.';
            messageElement.style.color = 'red';
        });

    // Handle form submission
    authForm.addEventListener('submit', (event) => {
        event.preventDefault();

        const formData = new FormData(authForm);
        const data = {
            uuid: formData.get('uuid'),
            code: formData.get('code'), // ADDED: Get the numeric verification code
            qq: parseInt(formData.get('qq')),
            meta: {}
        };

        // Collect custom field data
        customFieldsContainer.querySelectorAll('input').forEach(input => {
            // Ensure not to overwrite 'code' or 'uuid' from form data
            if (input.name !== 'uuid' && input.name !== 'qq' && input.name !== 'name' && input.name !== 'code') {
                data.meta[input.name] = input.value;
            }
        });

        fetch('/api/bind', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data)
        })
        .then(response => response.json())
        .then(result => {
            if (result.success) {
                // Redirect to success page
                window.location.href = 'success.html';
            } else {
                messageElement.textContent = result.error || '绑定失败。';
                messageElement.style.color = 'red';
            }
        })
        .catch(error => {
            console.error('Error during binding:', error);
            messageElement.textContent = '发生错误，请稍后再试。';
            messageElement.style.color = 'red';
        });
    });
});