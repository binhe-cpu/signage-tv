(function () {
  'use strict';

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function mkBtn(label, cls, disabled, fn) {
    var b = el('button', cls, label);
    b.type = 'button';
    b.disabled = !!disabled;
    b.addEventListener('click', fn);
    return b;
  }

  var MEDIA_RE = /\.(jpe?g|png|webp|bmp|mp4|m4v|mov|mkv|webm|ts|avi|3gp)$/i;

  var st = {
    storage: null,
    playlistName: 'playlist.json',
    revision: null,
    defaults: { imageDurationSec: 8, muted: true },
    order: [],
    objects: {}
  };

  var dragFrom = -1;
  var busy = false;

  // ------------------------------------------------------------ 基础

  function fmtSize(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1048576) return Math.round(n / 1024) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }

  function naturalKey(s) {
    return String(s).split(/(\d+)/).map(function (t) {
      return /^\d+$/.test(t) ? Number(t) : t.toLowerCase();
    });
  }

  function naturalCompare(a, b) {
    var ka = naturalKey(a), kb = naturalKey(b);
    var n = Math.max(ka.length, kb.length);
    for (var i = 0; i < n; i++) {
      var x = ka[i], y = kb[i];
      if (x === undefined) return -1;
      if (y === undefined) return 1;
      if (typeof x !== typeof y) return typeof x === 'number' ? -1 : 1;
      if (x < y) return -1;
      if (x > y) return 1;
    }
    return 0;
  }

  function api(method, path, body) {
    var opt = { method: method, credentials: 'same-origin' };
    if (body !== undefined && body !== null) {
      opt.headers = { 'Content-Type': 'application/json' };
      opt.body = JSON.stringify(body);
    }
    return fetch(path, opt).then(function (r) {
      return r.text().then(function (t) {
        var j = null;
        try { j = JSON.parse(t); } catch (e) { j = null; }
        if (r.status === 401) {
          showLogin();
          throw new Error((j && j.error) || '没登录或登录已过期');
        }
        if (!j) {
          throw new Error('服务返回了非预期内容（HTTP ' + r.status + '）：' + t.slice(0, 160));
        }
        if (!j.ok) throw new Error(j.error || ('HTTP ' + r.status));
        return j;
      });
    });
  }

  function setBusy(v) {
    busy = !!v;
    $('publishBtn').disabled = busy;
    $('pickBtn').disabled = busy;
  }

  function setPubMsg(text, kind) {
    var e = $('pubMsg');
    e.textContent = text || '';
    e.className = 'small' + (kind ? ' ' + kind : '');
  }

  // ------------------------------------------------------------ 渲染

  function applyState(s) {
    st.storage = s.storage || null;
    st.playlistName = s.playlistName || 'playlist.json';
    st.revision = (s.playlist && s.playlist.revision) || null;
    st.defaults = (s.playlist && s.playlist.defaults) || { imageDurationSec: 8, muted: true };

    st.objects = {};
    (s.objects || []).forEach(function (o) { st.objects[o.file] = o; });

    st.order = ((s.playlist && s.playlist.items) || []).map(function (it) {
      return {
        file: it.file,
        durationSec: it.durationSec,
        startTime: it.startTime,
        endTime: it.endTime,
        days: it.days
      };
    });

    $('imgDur').value = st.defaults.imageDurationSec || 8;
    $('muted').checked = st.defaults.muted !== false;

    renderMeta(s);
    renderBanner(s);
    renderCode(s);
    renderEndpoint(s);
    renderOrder();
    renderLib();

    // 预览层正开着的时候，这次的刷新可能正好把当前看的素材删掉了，
    // 得把列表和位置重算一遍，不然「下一个」会走到已经不存在的东西上。
    if (viewerOpen() && viewer.file) {
      var rest = Object.keys(st.objects).sort(naturalCompare);
      if (indexOfInList(rest, viewer.file) < 0) rest = [viewer.file];
      var same = rest.length === viewer.list.length;
      for (var vi = 0; same && vi < rest.length; vi++) {
        if (rest[vi] !== viewer.list[vi]) same = false;
      }
      viewer.list = rest;
      if (!same) showInViewer(viewer.file);
    }
    return s;
  }

  function renderMeta(s) {
    var stg = s.storage || {};
    var objCount = (s.objects || []).length;
    var line1 = '版本 ' + (st.revision || '还没有清单') +
                ' · 播放 ' + st.order.length + ' 项' +
                ' · 线上 ' + objCount + ' 个素材';

    var m = $('meta');
    m.innerHTML = '';
    m.appendChild(el('div', null, line1));
    m.appendChild(el('div', 'small', (stg.label || '存储') + '：' + (stg.target || '未配置')));
    m.appendChild(el('div', 'small', '清单：' + st.playlistName));
  }

  function renderBanner(s) {
    var b = $('banner');
    b.innerHTML = '';

    var msgs = [];
    if (s.storage && s.storage.problem) {
      msgs.push('存储位置不可用：' + s.storage.problem);
    }
    if (s.playlist && s.playlist.error) {
      msgs.push('清单读不出来：' + s.playlist.error);
    }
    if (s.missing && s.missing.length) {
      var head = s.missing.slice(0, 3).join('、');
      msgs.push('清单里有 ' + s.missing.length + ' 个素材在线上已经不存在了（' + head +
                (s.missing.length > 3 ? ' 等' : '') +
                '），电视会拉不到它们。请处理完重新发布一次。');
    }
    if (!msgs.length) {
      b.className = 'banner hidden';
      return;
    }
    msgs.forEach(function (t) { b.appendChild(el('div', null, t)); });
    b.className = 'banner';
  }

  function renderOrder() {
    var list = $('orderList');
    list.innerHTML = '';
    $('orderCount').textContent = st.order.length ? st.order.length + ' 项' : '';

    if (!st.order.length) {
      list.appendChild(el('div', 'empty', '还没有内容。在下面添加素材，或在素材库里点「加入」。'));
      return;
    }

    st.order.forEach(function (it, i) {
      var li = el('li');
      var obj = st.objects[it.file];

      li.appendChild(thumbNode(it.file, obj));
      li.appendChild(el('span', 'idx', String(i + 1)));

      var nm = nameNode(it.file);
      if (!obj) nm.style.color = 'var(--danger)';
      li.appendChild(nm);

      li.appendChild(el('span', 'sz', obj ? fmtSize(obj.size) : '素材缺失'));

      var acts = el('div', 'acts');
      acts.appendChild(mkBtn('↑', 'mini', i === 0, function () { moveItem(i, i - 1); }));
      acts.appendChild(mkBtn('↓', 'mini', i === st.order.length - 1, function () { moveItem(i, i + 1); }));
      acts.appendChild(mkBtn('移除', 'mini', false, function () { unschedule(it.file); }));
      li.appendChild(acts);

      li.draggable = true;
      bindDrag(li, i);
      list.appendChild(li);
    });
  }

  function renderLib() {
    var list = $('libList');
    list.innerHTML = '';

    var files = Object.keys(st.objects).sort(naturalCompare);
    $('libCount').textContent = files.length ? files.length + ' 个' : '';

    if (!files.length) {
      list.appendChild(el('div', 'empty', '线上还没有素材。先在上面传几个上去。'));
      return;
    }

    var pos = {};
    st.order.forEach(function (it, i) { pos[it.file] = i + 1; });

    files.forEach(function (f) {
      var o = st.objects[f];
      var li = el('li');
      li.appendChild(thumbNode(f, o));
      li.appendChild(nameNode(f));
      li.appendChild(el('span', 'sz', fmtSize(o.size)));

      var acts = el('div', 'acts');
      if (pos[f]) {
        li.appendChild(el('span', 'tag', '播放中 · 第 ' + pos[f] + ' 位'));
      } else {
        li.appendChild(el('span', 'tag', '未使用'));
        acts.appendChild(mkBtn('加入', 'mini', false, function () {
          st.order.push({ file: f });
          renderOrder();
          renderLib();
        }));
      }
      acts.appendChild(mkBtn('删除', 'mini danger', false, function () {
        deleteObjects([f]);
      }));
      li.appendChild(acts);

      list.appendChild(li);
    });
  }

  // ------------------------------------------------------------ 顺序调整

  function moveItem(from, to) {
    if (to < 0 || to >= st.order.length) return;
    var x = st.order.splice(from, 1)[0];
    st.order.splice(to, 0, x);
    renderOrder();
    renderLib();
  }

  function unschedule(file) {
    st.order = st.order.filter(function (it) { return it.file !== file; });
    renderOrder();
    renderLib();
  }

  // ------------------------------------------------------------ 素材内容查看

  var viewer = { file: null, list: [] };

  function previewUrl(file) {
    return '/api/preview?file=' + encodeURIComponent(file);
  }

  function indexOfInOrder(file) {
    for (var i = 0; i < st.order.length; i++) {
      if (st.order[i].file === file) return i;
    }
    return -1;
  }

  function indexOfInList(list, file) {
    for (var i = 0; i < list.length; i++) {
      if (list[i] === file) return i;
    }
    return -1;
  }

  /** 列表行首的缩略图：图片直接缩放显示，视频给个播放标记（首帧要转码，不做） */
  function thumbNode(file, obj) {
    if (!obj) {
      var miss = el('span', 'thumb thumb-ph', '缺');
      miss.title = '线上没有这个素材';
      return miss;
    }
    if (obj.type === 'video') {
      var box = el('span', 'thumb thumb-video', '\u25b6');
      box.title = '点一下看内容';
      box.addEventListener('click', function () { openViewer(file); });
      return box;
    }
    var img = document.createElement('img');
    img.className = 'thumb';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.alt = '';
    img.title = '点一下看内容';
    img.src = previewUrl(file);
    img.addEventListener('click', function () { openViewer(file); });
    // 取不到就退回一个方块，别在页面上留个破图
    img.addEventListener('error', function () {
      var ph = el('span', 'thumb thumb-ph', '?');
      ph.title = '缩略图取不到，点文件名看看';
      if (img.parentNode) img.parentNode.replaceChild(ph, img);
    });
    return img;
  }

  function nameNode(file, cls) {
    var nm = el('span', cls || 'nm', file);
    nm.classList.add('clickable');
    nm.title = '点一下看内容';
    nm.addEventListener('click', function () { openViewer(file); });
    return nm;
  }

  function openViewer(file) {
    var all = Object.keys(st.objects).sort(naturalCompare);
    if (indexOfInList(all, file) < 0) all = [file];   // 素材已经不在线上，也让它显示错误原因
    viewer.list = all;
    showInViewer(file);
    $('viewer').classList.remove('hidden');
    document.body.style.overflow = 'hidden';
  }

  function closeViewer() {
    $('viewer').classList.add('hidden');
    document.body.style.overflow = '';
    var stage = $('vStage');
    stage.innerHTML = '';           // 停掉正在播的视频，别在后台接着响
    stage.removeAttribute('data-file');
    viewer.file = null;
  }

  function showInViewer(file) {
    viewer.file = file;
    var obj = st.objects[file];
    var pos = indexOfInOrder(file);

    $('vName').textContent = file;
    var bits = [];
    if (obj) {
      bits.push(fmtSize(obj.size));
      bits.push(obj.type === 'video' ? '视频' : '图片');
    } else {
      bits.push('线上已经没有这个素材');
    }
    bits.push(pos >= 0 ? '播放顺序第 ' + (pos + 1) + ' 位'
                       : '未使用（下次发布不会上屏）');
    $('vMeta').textContent = bits.join(' · ');

    var i = indexOfInList(viewer.list, file);
    $('vPos').textContent = viewer.list.length > 1
      ? (i + 1) + ' / ' + viewer.list.length : '';
    $('vPrev').disabled = i <= 0;
    $('vNext').disabled = i < 0 || i >= viewer.list.length - 1;
    $('vOpen').href = previewUrl(file);

    var stage = $('vStage');
    // 同一个素材就别重挂媒体，否则刷新一次线上状态、视频就从头重放
    if (stage.getAttribute('data-file') === file) return;
    stage.setAttribute('data-file', file);
    stage.innerHTML = '';

    var node;
    if (obj && obj.type === 'video') {
      node = document.createElement('video');
      node.controls = true;
      node.autoplay = true;
      node.playsInline = true;
      node.preload = 'metadata';
    } else {
      node = document.createElement('img');
      node.alt = file;
    }
    node.addEventListener('error', function () {
      stage.innerHTML = '';
      stage.appendChild(el('div', 'msg',
        '这个素材取不出来。\n\n可能是文件已经从存储里被删掉了，或存储服务拒绝了读取请求。'
        + '\n可以点「新窗口打开」看浏览器给的错误码。'));
    });
    node.src = previewUrl(file);
    stage.appendChild(node);
  }

  function stepViewer(delta) {
    var i = indexOfInList(viewer.list, viewer.file);
    if (i < 0) return;
    var j = i + delta;
    if (j < 0 || j >= viewer.list.length) return;
    showInViewer(viewer.list[j]);
  }

  function viewerOpen() {
    return !$('viewer').classList.contains('hidden');
  }

  // ------------------------------------------------------------ 拖拽排序

  function bindDrag(li, idx) {
    li.addEventListener('dragstart', function (e) {
      dragFrom = idx;
      li.classList.add('dragging');
      try {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', String(idx));
      } catch (err) { /* 某些浏览器不给设，忽略 */ }
    });
    li.addEventListener('dragend', function () {
      li.classList.remove('dragging');
      dragFrom = -1;
    });
    li.addEventListener('dragover', function (e) {
      if (dragFrom < 0) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      li.classList.add('drop-target');
    });
    li.addEventListener('dragleave', function () {
      li.classList.remove('drop-target');
    });
    li.addEventListener('drop', function (e) {
      e.preventDefault();
      li.classList.remove('drop-target');
      if (dragFrom < 0 || dragFrom === idx) return;
      var moved = st.order.splice(dragFrom, 1)[0];
      st.order.splice(idx, 0, moved);
      dragFrom = -1;
      renderOrder();
      renderLib();
    });
  }

  // ------------------------------------------------------------ 上传

  function setProgress(pct, text, errors) {
    $('upBar').style.width = Math.max(0, Math.min(100, pct)) + '%';
    if (text != null) $('upText').textContent = text;
    showUpErrors(errors);
  }

  function showUpErrors(errors) {
    var ul = $('upErrors');
    ul.innerHTML = '';
    (errors || []).forEach(function (m) { ul.appendChild(el('li', null, m)); });
  }

  function sanitizeSub(v) {
    var s = String(v || '').trim().replace(/\\/g, '/').replace(/^\/+|\/+$/g, '');
    if (!s) return '';
    var parts = s.split('/').filter(function (p) {
      return p && p !== '.' && p !== '..' && p.charAt(0) !== '.';
    });
    parts = parts.map(function (p) {
      return p.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_');
    });
    return parts.slice(0, 3).join('/');
  }

  function pickFiles(fileList) {
    var files = Array.prototype.slice.call(fileList || []);
    if (!files.length) return;

    var media = [], rejected = [], seen = {};
    files.forEach(function (f) {
      if (!MEDIA_RE.test(f.name)) {
        rejected.push(f.name + '：不是支持的图片或视频格式，已跳过');
        return;
      }
      if (seen[f.name]) {
        rejected.push(f.name + '：这次选重了，只传一次');
        return;
      }
      seen[f.name] = 1;
      media.push(f);
    });

    if (!media.length) {
      $('upWrap').classList.remove('hidden');
      setProgress(0, '', rejected);
      return;
    }
    startUpload(media, rejected);
  }

  function startUpload(files, rejected) {
    var sub = sanitizeSub($('subdir').value);
    var names = files.map(function (f) { return sub ? sub + '/' + f.name : f.name; });

    $('upWrap').classList.remove('hidden');
    setProgress(0, '正在申请上传凭证…', rejected);
    setBusy(true);

    api('POST', '/api/presign', { names: names }).then(function (r) {
      var urls = {};
      (r.files || []).forEach(function (e) { urls[e.name] = e.url || null; });

      var errors = rejected.slice();
      var done = 0, okCount = 0;

      function step() {
        if (done >= files.length) {
          setProgress(100, '完成：成功 ' + okCount + ' / ' + files.length + ' 个', errors);
          setBusy(false);
          if (okCount > 0) {
            $('file').value = '';
            refresh().then(function () {
              if (!errors.length) {
                setProgress(100, '完成：' + okCount + ' 个素材已上传，'
                  + '在「播放顺序」里调整位置后点发布。', errors);
              }
            });
          }
          return;
        }

        var f = files[done];
        var rel = names[done];
        var base = done / files.length * 100;
        var span = 100 / files.length;
        var label = '(' + (done + 1) + '/' + files.length + ') ' + rel;

        var sender = urls[rel] ? putDirect : putProxy;
        sender(f, rel, urls[rel], function (frac) {
          setProgress(base + span * frac, '正在传 ' + label, errors);
        }).then(function () {
          okCount++;
          done++;
          setProgress(base + span, '已传完 ' + label, errors);
          step();
        }).catch(function (e) {
          errors.push(rel + '：' + e.message);
          done++;
          setProgress(base + span, '传 ' + label + ' 失败', errors);
          step();
        });
      }
      step();
    }).catch(function (e) {
      setBusy(false);
      setProgress(0, '', rejected.concat(['申请上传凭证失败：' + e.message]));
    });
  }

  function httpHint(status) {
    if (status === 403) {
      return '被存储服务拒绝（403）：预签名凭证可能已过期，' +
             '或者对象存储的跨域（CORS）规则没放行这个上传。';
    }
    if (status === 404) return '上传地址不存在（404）：存储桶或前缀可能配错了。';
    if (status >= 500) return '存储服务出错（' + status + '）。';
    return '上传失败（HTTP ' + status + '）。';
  }

  function putDirect(file, rel, url, onProgress) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('PUT', url, true);
      if (file.type) {
        try { xhr.setRequestHeader('Content-Type', file.type); } catch (e) { /* 忽略 */ }
      }
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable && e.total > 0) onProgress(e.loaded / e.total);
      };
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve();
        } else {
          var detail = String(xhr.responseText || '').replace(/\s+/g, ' ').slice(0, 200);
          reject(new Error(httpHint(xhr.status) + (detail ? ' ' + detail : '')));
        }
      };
      xhr.onerror = function () {
        reject(new Error('浏览器拦住了这个上传。多半是对象存储的跨域（CORS）规则没配好，'
                         + '或者网络不通。'));
      };
      xhr.ontimeout = function () { reject(new Error('上传超时')); };
      xhr.send(file);
    });
  }

  function putProxy(file, rel, _url, onProgress) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload?name=' + encodeURIComponent(rel), true);
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable && e.total > 0) onProgress(e.loaded / e.total);
      };
      xhr.onload = function () {
        var j = null;
        try { j = JSON.parse(xhr.responseText); } catch (e) { j = null; }
        if (j && j.ok) return resolve();
        reject(new Error((j && j.error) || ('HTTP ' + xhr.status)));
      };
      xhr.onerror = function () { reject(new Error('网络中断，请求没到服务')); };
      xhr.send(file);
    });
  }

  // ------------------------------------------------------------ 电视端地址

  function playlistUrlOnThisHost(s) {
    // 用浏览器当前访问的地址来拼：从局域网 IP 进来就是局域网地址，从域名进来就是域名。
    // 不让服务端自己算——它在云上时算出来的是内网 IP，填到电视上必然不通。
    return window.location.origin + '/' + (s.playlistName || 'playlist.json');
  }

  function renderEndpoint(s) {
    var a = $('tvUrl');
    var hint = $('tvHint');
    var name = s.playlistName || 'playlist.json';

    $('tvMode').textContent = (s.storage && s.storage.label) || '';
    hint.className = 'muted small hint';

    if (s.publicRead === false) {
      a.textContent = '这台服务器不提供电视端出口';
      a.removeAttribute('href');
      $('tvCopyBtn').disabled = true;
      hint.textContent = '素材存在对象存储上，电视端直接填桶的域名加清单名，例如 ' +
        'https://你的桶.cos.ap-shanghai.myqcloud.com/' + name +
        '。桶要设成公有读，电视才拉得到。';
      return;
    }

    var url = playlistUrlOnThisHost(s);
    var host = window.location.hostname;
    var isLocal = (host === '127.0.0.1' || host === 'localhost' || host === '::1');

    a.textContent = url;
    a.href = url;
    $('tvCopyBtn').disabled = false;

    if (isLocal) {
      // 从服务器本机打开的页面：127.0.0.1 只在这台机器上成立，电视不认这个地址
      hint.className = 'muted small hint warn';
      hint.textContent = '你现在是从这台服务器本机打开的，所以上面是 127.0.0.1 —— 这个地址' +
        '填到电视上没有用。把开头换成这台电脑在局域网里的 IP（启动日志里「局域网」那行，' +
        '一般 192.168. 开头），端口和路径照抄。';
    } else {
      hint.textContent = '这台服务器直接把素材发给电视，上面就是电视端要填的清单地址（免登录）。' +
        '打不开的话，先看这台电脑的防火墙有没有放行 ' + (window.location.port || '8600') + ' 端口。';
    }
  }

  function copyEndpoint() {
    var url = $('tvUrl').getAttribute('href') || $('tvUrl').textContent || '';
    var msg = $('tvCopyMsg');

    function done() {
      msg.className = 'small ok';
      msg.textContent = '已复制。';
    }
    function fallback() {
      msg.className = 'small bad';
      msg.textContent = '这个浏览器不让自动复制，手动选中下面这个地址复制：';
      window.prompt('复制这个地址：', url);
    }

    // 局域网是 http 访问，属于"非安全上下文"，navigator.clipboard 在这种页面里
    // 直接不可用；execCommand 虽然老，但到处都能用。
    var ta = document.createElement('textarea');
    ta.value = url;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    ok ? done() : fallback();
  }

  // ------------------------------------------------------------ 电视状态

  var devTimer = null;
  var devGotAt = 0;

  function ago(sec) {
    sec = Math.max(0, Math.round(sec));
    if (sec < 60) return sec + ' 秒前';
    if (sec < 3600) return Math.round(sec / 60) + ' 分钟前';
    if (sec < 86400) return (sec / 3600).toFixed(1) + ' 小时前';
    return Math.round(sec / 86400) + ' 天前';
  }

  function renderDevices(d) {
    var list = $('devList');
    if (!list) return;
    list.innerHTML = '';

    var rows = d.devices || [];
    var sum = $('devSum');
    if (sum) sum.textContent = rows.length ? (d.onlineCount + ' / ' + d.total + ' 台在线') : '';

    if (!rows.length) {
      list.appendChild(el('div', 'empty', '还没有任何屏上报过。'));
      return;
    }

    rows.forEach(function (r) {
      var box = el('div', 'dev ' + (r.online ? 'on' : 'off'));

      var h = el('div', 'dev-h');
      h.appendChild(el('span', 'dot'));
      h.appendChild(el('span', 'dev-nm', r.device || r.key));
      // 静默秒数是服务端算好的，但页面每 30 秒才拉一次。加上本地流逝的时间，
      // 页面上的「12 秒前」才会自己往前走，看着才像活的
      var silent = r.silentSec + (Date.now() - devGotAt) / 1000;
      h.appendChild(el('span', 'dev-when',
        (r.online ? '' : '最后见到 ') + ago(silent) + (r.ip ? '　' + r.ip : '')));
      box.appendChild(h);

      var l2 = [];
      if (r.version) l2.push(r.version);
      l2.push(r.mode === 'local' ? '本地目录' : (r.mode ? '远端清单' : '模式未知'));
      if (r.playableNow != null) {
        l2.push('播 ' + (r.playingIndex >= 0 ? (r.playingIndex + 1) : '-') + '/' + r.playableNow);
      }
      if (r.playingFile) l2.push(r.playingFile);
      box.appendChild(el('div', 'dev-l2', l2.join('　·　')));

      var l3 = [];
      if (r.revision) l3.push('清单 ' + String(r.revision).slice(0, 8));
      if (r.playlistTotal != null) l3.push('共 ' + r.playlistTotal + ' 项');
      l3.push('手机 ' + (r.manualCount || 0) + ' / 本地 ' + (r.localCount || 0) +
              ' / 远端 ' + (r.remoteCount || 0));
      var line3 = el('div', 'dev-l3', l3.join('　·　'));
      // 跟线上当前那一版对不上 = 这块屏还没更新上去。
      // 「改了内容但屏上没变」以前只能一趟趟跑店里看，这条是最省事的一次判断
      if (d.currentRevision && r.revision && r.revision !== d.currentRevision) {
        line3.appendChild(el('span', 'dev-stale',
          '还是旧版（线上现在是 ' + String(d.currentRevision).slice(0, 8) + '）'));
      }
      box.appendChild(line3);

      if (r.lastError) box.appendChild(el('div', 'dev-l3 dev-bad', '上次出错：' + r.lastError));
      if (!r.online && r.message) box.appendChild(el('div', 'dev-l3', '最后同步：' + r.message));

      list.appendChild(box);
    });
  }

  function loadDevices() {
    return api('GET', '/api/devices').then(function (d) {
      devGotAt = Date.now();
      renderDevices(d);
      var s = $('devSt');
      if (s) s.textContent = '';
    }).catch(function (e) {
      var s = $('devSt');
      if (s) s.textContent = '读电视状态失败：' + e.message;
    });
  }

  // 设备卡片**单独**定时刷新：不能跟着整个 state 一起刷 ——
  // 那会顺手重建「播放顺序」列表，用户正在输停留秒数的时候会被冲掉。
  function startDevPoll() {
    stopDevPoll();
    devTimer = setInterval(function () {
      if (document.hidden) return;   // 页面在后台就别刷了
      loadDevices();
    }, 30000);
  }

  function stopDevPoll() {
    if (devTimer) { clearInterval(devTimer); devTimer = null; }
  }

  // ------------------------------------------------------------ 发布 / 删除

  function doPublish() {
    if (busy) return;

    // 空清单是允许的（= 把远端内容全部下架），但要用户自己确认一次。
    // 屏上会退回本地目录继续播，不会黑屏。
    var allowEmpty = false;
    if (!st.order.length) {
      allowEmpty = window.confirm(
        '播放顺序是空的。\n\n' +
        '发布会把电视上的远端内容全部下架（素材文件还留在素材库，重新加回来不用重传）。\n' +
        '电视那边会退回播本地目录 media/ 里的东西；本地也没有就会显示待机提示。\n\n' +
        '确定要发布这份空清单吗？'
      );
      if (!allowEmpty) return;
    }

    var missing = st.order.filter(function (it) { return !st.objects[it.file]; });
    if (missing.length) {
      setPubMsg('有 ' + missing.length + ' 个播放项在线上找不到素材，先把它们移除或重新上传。', 'bad');
      return;
    }

    setBusy(true);
    setPubMsg('正在发布…', '');
    api('POST', '/api/publish', {
      items: st.order,
      allowEmpty: allowEmpty,
      imageDurationSec: Number($('imgDur').value) || 8,
      muted: $('muted').checked
    }).then(function (r) {
      setBusy(false);
      if (r.empty) {
        setPubMsg('已发布空清单（版本 ' + r.revision + '）：远端内容全部下架。' +
                  '电视最多 5 分钟后自己拉过去，届时会退回本地目录 media/。', 'ok');
      } else {
        setPubMsg('已发布 ' + r.count + ' 项，版本 ' + r.revision +
                  '。电视最多 5 分钟后自己拉过去。', 'ok');
      }
      return refresh();
    }).catch(function (e) {
      setBusy(false);
      setPubMsg('发布失败：' + e.message, 'bad');
    });
  }

  function deleteObjects(names) {
    var one = names.length === 1 ? names[0] : null;
    var inOrder = one && st.order.some(function (it) { return it.file === one; });
    var label = one || (names.length + ' 个素材');

    var msg = '删除 ' + label + '？\n\n会从线上把文件删掉。';
    if (inOrder) msg += '\n它正在播放顺序里，会一起被摘掉。';
    msg += '\n电视上正在播的内容也会跟着变。';
    if (!confirm(msg)) return;

    setBusy(true);
    api('POST', '/api/delete', { names: names }).then(function (r) {
      setBusy(false);
      if (r.failed && r.failed.length) {
        setPubMsg('删除完成 ' + r.deleted + ' 个，但有失败：' + r.failed.join('；'), 'bad');
      } else {
        var bits = ['已删除 ' + r.deleted + ' 个'];
        if (r.removedFromPlaylist && r.removedFromPlaylist.length) {
          bits.push('并从播放顺序里摘掉 ' + r.removedFromPlaylist.length + ' 项');
        }
        if (r.playlistEmptied) bits.push('清单已清空，屏上暂时没有内容了');
        setPubMsg(bits.join('，') + '。', 'ok');
      }
      return refresh();
    }).catch(function (e) {
      setBusy(false);
      setPubMsg('删除失败：' + e.message, 'bad');
    });
  }

  // ------------------------------------------------------------ 安全设置（改访问码）

  function codeSay(text, kind) {
    var e = $('codeMsg');
    e.textContent = text || '';
    e.className = 'small' + (kind ? ' ' + kind : '');
  }

  function renderCode(s) {
    var c = (s && s.code) || {};
    $('codeOrigin').textContent = c.origin || '';
    if (c.editable === false) {
      // 环境变量/命令行给的码，页面上改了下次重启又变回去 —— 那种"改了没用"比不让改更坏
      $('saveCodeBtn').disabled = true;
      codeSay('这个访问码来自' + (c.origin || '启动参数') +
              '，页面上改不了。要换就改服务器的 SIGNAGE_ADMIN_CODE（或 --admin-code）再重启。',
              'bad');
    }
  }

  function toggleCode(show) {
    var card = $('codeCard');
    var on = (show === undefined) ? card.classList.contains('hidden') : !!show;
    card.classList.toggle('hidden', !on);
    if (on) {
      try { $('curCode').focus(); } catch (e) { /* 忽略 */ }
    }
  }

  function saveCode() {
    if (busy) return;
    var cur = $('curCode').value;
    var n1 = $('newCode').value;
    var n2 = $('newCode2').value;
    if (!n1) { codeSay('新访问码不能空着', 'bad'); return; }
    if (n1 !== n2) { codeSay('两次输入的新访问码不一样', 'bad'); return; }

    setBusy(true);
    codeSay('正在保存…', '');
    api('POST', '/api/password', { current: cur, next: n1, confirm: n2 }).then(function () {
      setBusy(false);
      $('curCode').value = '';
      $('newCode').value = '';
      $('newCode2').value = '';
      codeSay('已改。别的设备上的登录已经失效，下次进来要输新码（这台不用）。', 'ok');
      setPubMsg('访问码已修改。', 'ok');
      return refresh();
    }).catch(function (e) {
      setBusy(false);
      codeSay('改失败：' + e.message, 'bad');
    });
  }

  // ------------------------------------------------------------ 登录 / 启动

  function showLogin() {
    $('login').classList.remove('hidden');
    $('app').classList.add('hidden');
  }

  function showApp() {
    $('login').classList.add('hidden');
    $('app').classList.remove('hidden');
  }

  function fetchState() {
    return api('GET', '/api/state').then(applyState);
  }

  function refresh() {
    loadDevices();
    if (!devTimer) startDevPoll();
    return fetchState().catch(function (e) {
      setPubMsg('读取线上状态失败：' + e.message, 'bad');
    });
  }

  function doLogin() {
    var code = $('code').value;
    $('loginErr').textContent = '';
    if (!code) {
      $('loginErr').textContent = '请输入访问码';
      return;
    }
    $('loginBtn').disabled = true;
    api('POST', '/api/login', { code: code }).then(function () {
      $('loginBtn').disabled = false;
      $('code').value = '';
      setPubMsg('', '');
      showApp();
      return refresh();
    }).catch(function (e) {
      $('loginBtn').disabled = false;
      $('loginErr').textContent = e.message;
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('loginBtn').addEventListener('click', doLogin);
    $('code').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') doLogin();
    });

    $('logoutBtn').addEventListener('click', function () {
      closeViewer();
      stopDevPoll();
      api('POST', '/api/logout').catch(function () {}).then(function () {
        showLogin();
      });
    });

    $('codeBtn').addEventListener('click', function () { toggleCode(); });
    $('hideCodeBtn').addEventListener('click', function () { toggleCode(false); });
    $('saveCodeBtn').addEventListener('click', saveCode);
    $('newCode2').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') saveCode();
    });

    $('pickBtn').addEventListener('click', function () { $('file').click(); });
    $('file').addEventListener('change', function () { pickFiles(this.files); });
    $('publishBtn').addEventListener('click', doPublish);
    $('tvCopyBtn').addEventListener('click', copyEndpoint);

    $('vClose').addEventListener('click', closeViewer);
    $('vPrev').addEventListener('click', function () { stepViewer(-1); });
    $('vNext').addEventListener('click', function () { stepViewer(1); });
    $('viewer').addEventListener('click', function (e) {
      if (e.target === $('viewer')) closeViewer();     // 点黑底也关掉
    });
    document.addEventListener('keydown', function (e) {
      if (!viewerOpen()) return;
      if (e.key === 'Escape') closeViewer();
      else if (e.key === 'ArrowLeft') stepViewer(-1);
      else if (e.key === 'ArrowRight') stepViewer(1);
    });

    var drop = $('drop');
    ['dragenter', 'dragover'].forEach(function (ev) {
      drop.addEventListener(ev, function (e) {
        e.preventDefault();
        drop.classList.add('over');
      });
    });
    ['dragleave', 'drop'].forEach(function (ev) {
      drop.addEventListener(ev, function (e) {
        e.preventDefault();
        drop.classList.remove('over');
      });
    });
    drop.addEventListener('drop', function (e) {
      if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
        pickFiles(e.dataTransfer.files);
      }
    });

    fetchState().then(showApp).catch(function () { showLogin(); });
  });
})();
