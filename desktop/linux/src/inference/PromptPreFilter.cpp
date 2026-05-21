#include "PromptPreFilter.h"

#include <array>
#include <utility>

namespace localyze {

namespace {

const std::array<QRegularExpression, 38> kInjectPatterns = {
    QRegularExpression(R"(\bignore\s+(all\s+|the\s+)?previous\s+instructions?\b)",       QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bignore\s+(any|all)\s+(previous|prior)\b)",                  QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bignore\s+your\s+training\b)",                               QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bignore\s+your\s+(previous\s+|prior\s+)?instructions?\b)",   QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bdisregard\s+(all\s+|the\s+)?(previous|prior)\s+(instructions?|rules?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bdisregard\s+(all\s+|the\s+)?rules?\b)",                     QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\brespond\s+as\s+if\s+you\s+(were|are)\s+(not|no\s+longer)\s+an?\s+ai\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bact\s+as\s+if\s+you\s+(were|are)\s+(not|no\s+longer)\s+an?\s+ai\b)",     QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bpretend\s+(you\s+have|you\s+are|to\s+have)\s+no\s+(safety|restrictions?|guidelines?|rules?|filter|values|training|limits?|polic(y|ies)|ethics|constraints?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bbehave\s+as\s+if\s+you\s+have\s+no\s+(safety|restrictions?|guidelines?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\byou\s+have\s+no\s+(safety|restrictions?|guidelines?|rules?|values|filter|limits?|polic(y|ies)|ethics|constraints?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\breply\s+(only\s+)?(with\s+)?(the\s+word\s+)?["']?\w+["']?\s*(and|then)?\s*(nothing|only)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bjust\s+say\s+["']?\w+["']?\s*(and|then)?\s*(nothing|stop)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bjust\s+output\s+["']?\w+["']?)",                            QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bsay\s+(exactly|just|only)\s+["']?\w+["']?\b)",              QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\brespond\s+(only\s+)?with\s+(only\s+)?(the\s+\w+\s+)?["']?\w+["']?\s*\.?\s*$)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bpwned\b)",                                                  QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\$\{jndi:)",                                                  QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bldap://)",                                                  QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\brepeat\s+your\s+(system\s+|initial\s+)?(prompt|instructions?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bprint\s+your\s+(system\s+|initial\s+)?(prompt|instructions?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bshow\s+(me\s+)?your\s+(system\s+|initial\s+|hidden\s+)?(prompt|instructions?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\b(echo|reveal|paraphrase)\s+your\s+(system\s+|initial\s+|hidden\s+)?(prompt|instructions?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\btell\s+me\s+your\s+(system\s+|initial\s+|hidden\s+)?(prompt|instructions?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bwhat\s+(is|are)\s+your\s+(system\s+|initial\s+)?(prompt|instructions?|rules?|guidelines?)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\boutput\s+the\s+text\s+(of\s+)?your\s+(system\s+)?prompt\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bact\s+as\s+(DAN|a\s+jailbroken|an?\s+unrestricted|an?\s+uncensored)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\byou\s+are\s+(now\s+)?(an?\s+)?(DAN|jailbroken|unrestricted|uncensored)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\boverride\s+(all\s+)?(safety|restrictions?|filters?)\b)",    QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\benable\s+(developer|admin|root)\s+mode\b)",                 QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bdeveloper\s+mode\s+(now|on|enabled?)\b)",                   QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\b(in|enter)\s+developer\s+mode\b)",                          QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\b(no\s+longer\s+bound|free\s+from)\s+(by\s+)?your\s+(training|rules|guidelines)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bforget\s+(your\s+(instructions?|training|rules?|guidelines?)|you\s+are\s+\w+)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\byour\s+new\s+name\s+is\s+\w+)",                             QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bbe\s+honest\s+(without\s+)?your\s+(restrictions?|guidelines?|training)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\banswer\s+honestly\s+without\s+(your\s+)?(usual\s+)?(restrictions?|guidelines?|training)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bsystem\s*:\s*ignore\b)",                                    QRegularExpression::CaseInsensitiveOption),
};

// Hard-refusal patterns: requests for genuinely operational, dangerous content.
// Match BEFORE the model is invoked so we don't depend on it complying.
const std::array<QRegularExpression, 18> kHardRefusePatterns = {
    QRegularExpression(R"(\bwrite\s+(me\s+)?a?\s*(keylogger|ransomware|spyware|stalkerware)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bwrite\s+(me\s+)?a?\s*(malware|virus|trojan|backdoor|rootkit)\b)",    QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\b(give|show|tell)\s+me\s+(how\s+to\s+)?(make|build|create|synthesi[sz]e|cook)\s+(a\s+)?(bomb|ied|explosive|pipe\s+bomb|grenade|c-?4|napalm|tnt)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bbomb\s+(instructions?|recipe|plans?)\b)",                            QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bhow\s+(do\s+i|to)\s+(make|build|construct|assemble)\s+(a\s+)?(bomb|ied|explosive|pipe\s+bomb|grenade|c-?4|napalm)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bsynthesi[sz]e\s+(meth|methamphetamine|cocaine|crack|heroin|fentanyl|lsd|mdma|ecstasy)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bhow\s+(do\s+i|to)\s+(make|cook|synthesi[sz]e)\s+(meth|methamphetamine|cocaine|crack|heroin|fentanyl|lsd)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bpick\s+(a\s+)?lock\b)",                                              QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bhow\s+(do\s+i|to)\s+pick\s+a\s+(door|car|house|padlock)\s+lock\b)",  QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bhow\s+(do\s+i|to)\s+(break\s+into|hot[\s-]?wire)\s+(a\s+)?(car|house|home|apartment)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\b(dox|doxx)(ing)?\s+(someone|a\s+person|my\s+\w+)\b)",               QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bhow\s+(do\s+i|to)\s+(stalk|track|monitor)\s+(my\s+)?(ex|girlfriend|boyfriend|wife|husband|partner)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bhow\s+(do\s+i|to)\s+evade\s+(the\s+)?(police|law\s+enforcement|cops|fbi|atf)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\b(build|make|create)\s+(a\s+)?(chemical|biological|nuclear|dirty)\s+(weapon|bomb)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\b(give|show)\s+me\s+a?\s*(working\s+|functional\s+)?(exploit|0day|zero[\s-]?day)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bwrite\s+(me\s+)?a?\s*(working\s+)?phishing\s+(email|page|kit|site)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bhow\s+(do\s+i|to)\s+(buy|acquire|obtain)\s+(illegal\s+)?(guns?|firearms?|drugs?)\s+(online|on\s+the\s+dark\s*web|without\s+id)\b)", QRegularExpression::CaseInsensitiveOption),
    QRegularExpression(R"(\bcredit\s+card\s+(skimmer|skimming)\s+(code|script)\b)",              QRegularExpression::CaseInsensitiveOption),
};

constexpr auto kHardRefusal =
    "I cannot help with that. I will not provide instructions for activities "
    "that are illegal or can cause harm.";

const QRegularExpression kInjectTopicAllow{
    R"(\b(what\s+is|explain|describe)\s+(prompt\s+injection|injection\s+attack|log4j|jndi)\b)",
    QRegularExpression::CaseInsensitiveOption
};

constexpr auto kInjectRefusal =
    "I will not follow that. The request is not something I will do.";

struct VaguePattern {
    QRegularExpression rx;
    std::vector<std::pair<QString, QStringList>> questions;
};

const std::vector<VaguePattern>& vaguePatterns() {
    static const std::vector<VaguePattern> table = {
        { QRegularExpression(
            R"(^\s*(top\s+\d+\s+)?news\b|^\s*what(?:'s| is)?\s+(?:the\s+|in\s+the\s+)?news\b|^\s*biggest\s+\w+\s+news\b|^\s*(latest|recent)\s+(news|updates?|stories|headlines)\s*\??\s*$)",
            QRegularExpression::CaseInsensitiveOption),
          {{"About what topic?",  {"finance","tech","sports","politics","world"}},
           {"From which region?", {"US","India","UK","EU","global"}},
           {"What time window?",  {"today","this week","this month"}}} },
        { QRegularExpression(
            R"(\b(recommend|suggest|pick|find|best)\s+(a\s+|an\s+|some\s+)?(stock|stocks|shares|investment)\b)",
            QRegularExpression::CaseInsensitiveOption),
          {{"Risk tolerance?",   {"low","moderate","high"}},
           {"Time horizon?",     {"short-term (<1yr)","medium (1-5yr)","long (5+yr)"}},
           {"Sector preference?",{"tech","finance","energy","healthcare","any"}}} },
        { QRegularExpression(
            R"(\b(best|recommend|suggest|which)\s+(a\s+|an\s+)?(phone|laptop|car|ev|evs|tablet|headphones?|earbuds|camera|tv|monitor)\b)",
            QRegularExpression::CaseInsensitiveOption),
          {{"Budget range?", {"under $500","$500-1000","$1000-2000","$2000+"}},
           {"Primary use?",  {"work","gaming","travel","video editing","general"}},
           {"Region?",       {"US","UK","India","EU","global"}}} },
        { QRegularExpression(
            R"(^\s*(help|assist)\s+me\s+with\s+(my\s+)?(taxes|finances?|career|diet|fitness|health|relationship|sleep|focus)\s*\.?\s*$)",
            QRegularExpression::CaseInsensitiveOption),
          {{"What specifically?", {"a question","a plan","review my situation","general guidance"}},
           {"Country / region?",  {"US","India","UK","EU","other"}},
           {"How urgent?",        {"right now","this week","this month","general"}}} },
        { QRegularExpression(
            R"(^\s*(give\s+me|any|got)\s+(some\s+)?advice\s*\.?\s*$)",
            QRegularExpression::CaseInsensitiveOption),
          {{"What domain?",          {"career","money","health","relationships","learning"}},
           {"What's your situation?",{"just starting","stuck","doing well, want more","between options"}}} },
        { QRegularExpression(
            R"(^\s*tell\s+me\s+something\s+(interesting|cool|fun)\b)",
            QRegularExpression::CaseInsensitiveOption),
          {{"About what topic?",   {"history","science","tech","space","nature","random"}},
           {"How long an answer?", {"one-liner","short paragraph","detailed"}}} },
        { QRegularExpression(
            R"(^\s*how(?:'s|\s+is)\s+the\s+(market|economy|weather)\b)",
            QRegularExpression::CaseInsensitiveOption),
          {{"Which market / sector?", {"S&P 500","NASDAQ","tech","energy","specific stock"}},
           {"Region?",                {"US","India","Europe","global"}},
           {"What do you want to know?",{"today's move","YTD performance","outlook"}}} },
        { QRegularExpression(
            R"(\b(recommend|suggest|pick|find)\s+(a\s+|an\s+|some\s+)?(movies?|films?|books?|songs?|albums?|restaurants?|shows?|series|podcasts?)\b)",
            QRegularExpression::CaseInsensitiveOption),
          {{"Genre / style?", {"drama","comedy","thriller","documentary","any"}},
           {"Mood right now?",{"light","thought-provoking","fun","intense"}},
           {"Time / length?", {"short","medium","long","any"}}} },
        { QRegularExpression(
            R"(^\s*should\s+i\s+(quit|leave|switch|change|resign\s+from|stay\s+at|stay\s+in|take|accept|reject|decline)\s+(my\s+)?(job|role|career|company|position|offer|internship|grad\s+school|phd|startup|business|relationship)\b)",
            QRegularExpression::CaseInsensitiveOption),
          {{"What's pushing you to consider this?", {"burnout / stress","comp / pay","career growth","team / culture","personal life"}},
           {"How urgent?",                          {"right now","this quarter","this year","exploring"}},
           {"What does success look like in 1 year?",{"more $","more meaning","more time","more growth","more stability"}}} },
        { QRegularExpression(
            R"(^\s*i'?m\s+(having\s+a\s+hard\s+time|struggling|stuck|burned?\s*out|exhausted|overwhelmed|lost|sad|down|anxious|stressed)\s*\.?\s*$)",
            QRegularExpression::CaseInsensitiveOption),
          {{"What's going on?",                    {"work","family","relationship","money","health","school"}},
           {"How long has this been a thing?",     {"a few days","a few weeks","months","longer"}},
           {"What would help most right now?",     {"someone to listen","practical advice","a plan","just venting"}}} },
    };
    return table;
}

QString formatClarification(const std::vector<std::pair<QString, QStringList>>& qs) {
    QString out = QStringLiteral("Quick question first — to give you a useful answer:");
    int i = 1;
    for (const auto& [q, opts] : qs) {
        out += QStringLiteral("\n%1. %2 (").arg(i++).arg(q);
        for (int k = 0; k < opts.size(); ++k) {
            if (k > 0) out += QStringLiteral(" / ");
            out += QStringLiteral("**%1**").arg(opts[k]);
        }
        out += QStringLiteral(")");
    }
    return out;
}

bool looksLikeInjection(const QString& p) {
    if (kInjectTopicAllow.match(p).hasMatch()) return false;
    for (const auto& rx : kInjectPatterns) {
        if (rx.match(p).hasMatch()) return true;
    }
    return false;
}

bool looksLikeHardRefuse(const QString& p) {
    for (const auto& rx : kHardRefusePatterns) {
        if (rx.match(p).hasMatch()) return true;
    }
    return false;
}

}  // namespace

PromptPreFilter::Decision PromptPreFilter::decide(const QString& prompt) {
    const QString p = prompt.trimmed();
    // Hard refusals fire FIRST — they cover operational instructions for
    // crimes / physical harm and must short-circuit before anything else.
    if (looksLikeHardRefuse(p)) {
        return {false, QString::fromUtf8(kHardRefusal), "hard_refusal"};
    }
    if (looksLikeInjection(p)) {
        return {false, QString::fromUtf8(kInjectRefusal), "injection"};
    }
    for (const auto& v : vaguePatterns()) {
        if (v.rx.match(p).hasMatch()) {
            return {false, formatClarification(v.questions), "vague"};
        }
    }
    return {true, {}, "passthrough"};
}

}  // namespace localyze
