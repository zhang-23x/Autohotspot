const fs = require('fs');
const p = 'D:/deepseek/EDA/autohotspot/src/com/yg/autohotspot/PhoneServer.java';
let src = fs.readFileSync(p, 'utf8');
let i = src.indexOf('private static final String PAGE');
let seg = src.slice(i);
let out = '', m;
let re = /"((?:[^"\\]|\\.)*)"/g;
while((m = re.exec(seg)) !== null){
  out += m[1].replace(/\\"/g, '"').replace(/\\n/g, '\n').replace(/\\\\/g, '\\');
}
fs.writeFileSync('D:/deepseek/EDA/autohotspot/p58.html', out);
let si = out.indexOf('<script>'), ei = out.lastIndexOf('</script>');
let code = out.slice(si + 8, ei);
fs.writeFileSync('D:/deepseek/EDA/autohotspot/p58.js', code);
console.log('html', out.length, 'js', code.length, 'si', si, 'ei', ei);
console.log('tail: ' + JSON.stringify(out.slice(-60)));
try { new Function(code); console.log('JS SYNTAX OK'); }
catch(err){ console.log('JS ERROR: ' + err.message); }
