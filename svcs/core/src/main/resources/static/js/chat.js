/*
 * Chat capture screen (F-1.11).
 *
 * The rule the whole file is built around: the backend proposes, the user
 * commits. Nothing here calls /confirm except the Create button, and Create is
 * only enabled for a draft the backend marked PARSED.
 *
 * Two ways to answer a question, both ending in the same draft:
 *   - a chip is a structured answer      -> POST /api/v1/chat/draft (no model call)
 *   - anything typed is language         -> POST /api/v1/chat       (read, then merged)
 */
(function () {
    'use strict';

    var API = '/api/v1';
    var TOKEN_KEY = 'zenz.accessToken';
    var PLACEHOLDER = 'e.g. I spent $20 — or ask how to spend less';

    var state = {
        token: sessionStorage.getItem(TOKEN_KEY),
        sessionId: null,
        messageId: null,
        draft: null,
        status: null,
        categories: []
    };

    var el = {};
    ['signin', 'email', 'password', 'signin-error', 'conversation', 'transcript', 'chips',
     'insight', 'insight-months',
     'preview', 'p-type', 'p-amount', 'p-category', 'p-note', 'p-date', 'edit', 'e-type',
     'e-amount', 'e-category', 'e-note', 'e-date', 'edit-cancel', 'preview-actions', 'create',
     'edit-open', 'cancel', 'preview-error', 'composer', 'message', 'send', 'chat-error']
        .forEach(function (id) { el[id] = document.getElementById(id); });

    // --- money and dates: formatting lives here, never on the backend ---

    function fractionDigits(currency) {
        try {
            return new Intl.NumberFormat('en', { style: 'currency', currency: currency })
                .resolvedOptions().maximumFractionDigits;
        } catch (e) {
            return 2;
        }
    }

    function toMajor(minor, currency) {
        return minor / Math.pow(10, fractionDigits(currency));
    }

    function formatMoney(minor, currency) {
        if (minor === null || minor === undefined) { return null; }
        var major = toMajor(minor, currency);
        try {
            return new Intl.NumberFormat(undefined, { style: 'currency', currency: currency }).format(major);
        } catch (e) {
            return major.toFixed(fractionDigits(currency)) + ' ' + (currency || '');
        }
    }

    function toMinor(major, currency) {
        // Scale first, then round, so 17.50 is 1750 and never 1749.
        return Math.round(Number(major) * Math.pow(10, fractionDigits(currency)));
    }

    function formatDate(millis) {
        if (!millis) { return null; }
        return new Date(millis).toLocaleDateString(undefined,
            { year: 'numeric', month: 'short', day: 'numeric' });
    }

    function dateInputValue(millis) {
        var d = new Date(millis || Date.now());
        var pad = function (n) { return String(n).padStart(2, '0'); };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
    }

    // --- transport ---

    function api(path, options) {
        options = options || {};
        var headers = { 'Content-Type': 'application/json' };
        if (state.token) { headers.Authorization = 'Bearer ' + state.token; }
        return fetch(API + path, {
            method: options.method || 'GET',
            headers: headers,
            body: options.body ? JSON.stringify(options.body) : undefined
        }).then(function (response) {
            // Every response is the ApiResponse envelope: {status, data, message, errorCode}.
            return response.json().catch(function () { return {}; }).then(function (envelope) {
                if (response.ok && envelope && envelope.status === 'success') {
                    return envelope.data;
                }
                if (response.status === 401) { signOut(); }
                throw new Error((envelope && envelope.message)
                    || 'Something went wrong. Please try again.');
            });
        });
    }

    function showError(node, error) {
        node.textContent = error ? error.message : '';
        node.hidden = !error;
    }

    // --- sign in ---

    function signOut() {
        state.token = null;
        sessionStorage.removeItem(TOKEN_KEY);
        el.signin.hidden = false;
        el.conversation.hidden = true;
    }

    function start() {
        el.signin.hidden = true;
        el.conversation.hidden = false;
        el.message.focus();
        api('/categories').then(function (categories) {
            state.categories = categories || [];
        }).catch(function () {
            // Only the edit form's dropdown needs these; chat itself works without them.
            state.categories = [];
        });
    }

    el.signin.addEventListener('submit', function (event) {
        event.preventDefault();
        showError(el['signin-error'], null);
        api('/authenticate', {
            method: 'POST',
            body: { email: el.email.value.trim(), password: el.password.value }
        }).then(function (auth) {
            state.token = auth.accessToken;
            sessionStorage.setItem(TOKEN_KEY, state.token);
            el.password.value = '';
            start();
        }).catch(function (error) { showError(el['signin-error'], error); });
    });

    // --- transcript ---

    function appendTurn(role, text) {
        var item = document.createElement('li');
        item.className = 'turn ' + role;
        item.textContent = text;
        el.transcript.appendChild(item);
        item.scrollIntoView({ block: 'nearest' });
    }

    // --- chips ---

    function renderChips(prompt) {
        el.chips.textContent = '';
        if (!prompt || !prompt.options.length) {
            el.chips.hidden = true;
            return;
        }
        prompt.options.forEach(function (option) {
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = option.freeform
                ? option.label
                : (option.label || formatMoney(option.amountMinor, state.draft && state.draft.currency));
            button.addEventListener('click', function () {
                if (option.freeform) {
                    // No list covers everything; typing falls through to the normal read path.
                    el.message.placeholder = 'Type the ' + prompt.field + '…';
                    el.message.focus();
                    return;
                }
                answer(prompt.field, option);
            });
            el.chips.appendChild(button);
        });
        el.chips.hidden = false;
    }

    function answer(field, option) {
        var body = { messageId: state.messageId };
        if (field === 'amount') { body.amountMinor = option.amountMinor; }
        if (field === 'category') { body.categoryId = option.value; }
        if (field === 'type') { body.txnType = option.value; }
        appendTurn('user', option.label
            || formatMoney(option.amountMinor, state.draft && state.draft.currency));
        send('/chat/draft', body, el['chat-error']);
    }

    // --- answered question ---

    function renderInsight(insight) {
        el['insight-months'].textContent = '';
        if (!insight || !insight.months || !insight.months.length) {
            el.insight.hidden = true;
            return;
        }
        insight.months.forEach(function (month) {
            var block = document.createElement('div');
            block.className = 'insight-month';

            var heading = document.createElement('h3');
            heading.textContent = month.month;
            block.appendChild(heading);

            var totals = document.createElement('p');
            totals.textContent = 'In ' + formatMoney(month.income, insight.currency)
                + ' · Out ' + formatMoney(month.expenses, insight.currency)
                + ' · Left ' + formatMoney(month.position, insight.currency);
            block.appendChild(totals);

            if (month.categories.length) {
                var rows = document.createElement('dl');
                rows.className = 'insight-rows';
                month.categories.forEach(function (category) {
                    var name = document.createElement('dt');
                    name.textContent = category.name;
                    var amount = document.createElement('dd');
                    amount.textContent = formatMoney(category.amount, insight.currency);
                    rows.appendChild(name);
                    rows.appendChild(amount);
                });
                block.appendChild(rows);
            }
            el['insight-months'].appendChild(block);
        });
        el.insight.hidden = false;
    }

    // --- preview ---

    function text(node, value) {
        node.textContent = value === null || value === undefined ? 'Not set yet' : value;
        node.classList.toggle('pending', value === null || value === undefined);
    }

    function renderPreview() {
        var draft = state.draft;
        if (!draft || draft.intent !== 'CREATE_TRANSACTION') {
            el.preview.hidden = true;
            return;
        }
        el.preview.hidden = false;
        text(el['p-type'], draft.txnType === 'INCOME' ? 'Income'
            : draft.txnType === 'EXPENSE' ? 'Expense' : null);
        text(el['p-amount'], formatMoney(draft.amountMinor, draft.currency));
        el['p-amount'].classList.add('amount');
        text(el['p-category'], draft.categoryName);
        text(el['p-note'], draft.note);
        text(el['p-date'], formatDate(draft.txnDate));

        var ready = state.status === 'PARSED';
        el.create.disabled = !ready;
        el.create.textContent = draft.txnType === 'INCOME' ? 'Create Income' : 'Create Expense';
        el.create.title = ready ? '' : 'Answer the question above first';
    }

    function fillEditForm() {
        var draft = state.draft;
        el['e-type'].value = draft.txnType || 'EXPENSE';
        el['e-amount'].value = draft.amountMinor === null || draft.amountMinor === undefined
            ? '' : toMajor(draft.amountMinor, draft.currency);
        el['e-amount'].step = Math.pow(10, -fractionDigits(draft.currency)).toFixed(
            fractionDigits(draft.currency)) || '1';
        el['e-note'].value = draft.note || '';
        el['e-date'].value = dateInputValue(draft.txnDate);

        el['e-category'].textContent = '';
        var kind = el['e-type'].value === 'INCOME' ? 'INCOME' : 'EXPENSE';
        state.categories.filter(function (c) { return c.kind === kind; }).forEach(function (c) {
            var option = document.createElement('option');
            option.value = c.id;
            option.textContent = c.name;
            option.selected = c.id === draft.categoryId;
            el['e-category'].appendChild(option);
        });
    }

    el['e-type'].addEventListener('change', function () {
        // The category list is kind-specific, so flipping the direction re-offers it.
        var current = el['e-category'].value;
        fillEditForm();
        el['e-category'].value = current;
    });

    el['edit-open'].addEventListener('click', function () {
        fillEditForm();
        el.edit.hidden = false;
        el['preview-actions'].hidden = true;
    });

    el['edit-cancel'].addEventListener('click', function () {
        el.edit.hidden = true;
        el['preview-actions'].hidden = false;
    });

    el.edit.addEventListener('submit', function (event) {
        event.preventDefault();
        var body = {
            messageId: state.messageId,
            txnType: el['e-type'].value,
            categoryId: el['e-category'].value || null,
            note: el['e-note'].value,
            txnDate: el['e-date'].value ? new Date(el['e-date'].value + 'T00:00:00').getTime() : null
        };
        if (el['e-amount'].value !== '') {
            body.amountMinor = toMinor(el['e-amount'].value, state.draft.currency);
        }
        el.edit.hidden = true;
        el['preview-actions'].hidden = false;
        send('/chat/draft', body, el['preview-error']);
    });

    el.create.addEventListener('click', function () {
        showError(el['preview-error'], null);
        el.create.disabled = true;
        api('/chat/confirm', { method: 'POST', body: { messageId: state.messageId } })
            .then(function (transaction) {
                appendTurn('assistant', 'Added — ' +
                    formatMoney(transaction.amount, transaction.currency) + '.');
                clearDraft();
            })
            .catch(function (error) {
                showError(el['preview-error'], error);
                el.create.disabled = false;
            });
    });

    el.cancel.addEventListener('click', function () {
        showError(el['preview-error'], null);
        api('/chat/reject', { method: 'POST', body: { messageId: state.messageId } })
            .then(function () {
                appendTurn('assistant', 'Discarded. Nothing was added to your ledger.');
                clearDraft();
            })
            .catch(function (error) { showError(el['preview-error'], error); });
    });

    function clearDraft() {
        state.messageId = null;
        state.draft = null;
        state.status = null;
        el.preview.hidden = true;
        el.edit.hidden = true;
        el['preview-actions'].hidden = false;
        renderChips(null);
        el.message.placeholder = PLACEHOLDER;
        el.message.focus();
    }

    // --- one round trip, however it was triggered ---

    function send(path, body, errorNode) {
        showError(errorNode, null);
        el.send.disabled = true;
        return api(path, { method: 'POST', body: body })
            .then(function (reply) {
                state.sessionId = reply.sessionId || state.sessionId;
                state.messageId = reply.messageId;
                state.draft = reply.draft;
                state.status = reply.status;
                appendTurn('assistant', reply.reply);
                renderChips(reply.prompt);
                renderPreview();
                // An answer and a draft are alternatives, never both — the backend
                // sends exactly one of them, so rendering both would show stale data.
                renderInsight(reply.insight);
            })
            .catch(function (error) { showError(errorNode, error); })
            .finally(function () {
                el.send.disabled = false;
                el.message.focus();
            });
    }

    el.composer.addEventListener('submit', function (event) {
        event.preventDefault();
        var message = el.message.value.trim();
        if (!message) { return; }
        appendTurn('user', message);
        el.message.value = '';
        el.message.placeholder = PLACEHOLDER;
        send('/chat', { message: message, sessionId: state.sessionId }, el['chat-error']);
    });

    if (state.token) { start(); } else { el.signin.hidden = false; }
}());
